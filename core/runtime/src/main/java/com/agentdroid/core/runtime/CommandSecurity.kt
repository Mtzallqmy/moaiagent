package com.agentdroid.core.runtime

import com.agentdroid.core.agent.RiskLevel
import java.io.File
import java.security.MessageDigest

private enum class ShellTokenKind { WORD, OPERATOR }
private data class ShellToken(val text: String, val kind: ShellTokenKind)

data class ParsedCommand(val words: List<String>, val operatorsAfter: List<String> = emptyList()) {
    val executable: String get() = words.firstOrNull().orEmpty()
}

data class CommandAssessment(
    val risk: RiskLevel,
    val pattern: String,
    val normalized: String,
    val cwd: File,
    val commands: List<ParsedCommand>,
    val blockedReason: String? = null
) {
    val allowed: Boolean get() = blockedReason == null
}

class CommandParseException(message: String) : IllegalArgumentException(message)

object CommandTokenizer {
    private val operators = listOf("2>>", "2>", "&&", "||", ">>", ";", "|", ">", "<", "&")

    fun parse(input: String): List<ParsedCommand> {
        if (input.isBlank()) throw CommandParseException("Command is blank")
        if ('\u0000' in input || '\n' in input || '\r' in input) throw CommandParseException("Multiline or NUL commands are not allowed")
        val tokens = lex(input)
        val result = mutableListOf<ParsedCommand>()
        val words = mutableListOf<String>()
        val operatorsAfter = mutableListOf<String>()
        fun flush() {
            if (words.isNotEmpty()) {
                result += ParsedCommand(words.toList(), operatorsAfter.toList())
                words.clear(); operatorsAfter.clear()
            }
        }
        tokens.forEach { token ->
            if (token.kind == ShellTokenKind.WORD) words += token.text
            else {
                val op = token.text
                if (op in setOf(";", "&&", "||", "|", "&")) {
                    operatorsAfter += op
                    flush()
                } else {
                    // Keep redirects on the command so classification can escalate writes.
                    words += op
                }
            }
        }
        flush()
        if (result.isEmpty()) throw CommandParseException("No executable command found")
        return result
    }

    private fun lex(input: String): List<ShellToken> {
        val out = mutableListOf<ShellToken>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var index = 0
        fun flushWord() {
            if (current.isNotEmpty()) {
                out += ShellToken(current.toString(), ShellTokenKind.WORD)
                current.setLength(0)
            }
        }
        while (index < input.length) {
            val ch = input[index]
            if (escaped) {
                current.append(ch); escaped = false; index++; continue
            }
            if (ch == '\\' && quote != '\'') {
                escaped = true; index++; continue
            }
            if (quote != null) {
                if (ch == quote) quote = null else current.append(ch)
                index++; continue
            }
            if (ch == '\'' || ch == '"') {
                quote = ch; index++; continue
            }
            if (ch.isWhitespace()) {
                flushWord(); index++; continue
            }
            val op = operators.firstOrNull { input.startsWith(it, index) }
            if (op != null) {
                flushWord(); out += ShellToken(op, ShellTokenKind.OPERATOR); index += op.length; continue
            }
            current.append(ch); index++
        }
        if (escaped) throw CommandParseException("Trailing escape")
        if (quote != null) throw CommandParseException("Unterminated quote")
        flushWord()
        return out
    }
}

class CommandClassifier {
    private val safe = setOf("pwd", "ls", "cat", "head", "tail", "wc", "grep", "printf", "echo", "stat", "du", "df", "sort", "uniq", "cut", "tr", "test", "true", "false", "which", "basename", "dirname")
    private val modify = setOf("mkdir", "touch", "cp", "mv", "chmod", "ln")
    private val destructive = setOf("rm", "rmdir", "truncate", "shred")
    private val external = setOf("curl", "wget", "ssh", "scp", "sftp", "nc", "ncat", "telnet")
    private val wrappers = setOf("sh", "bash", "dash", "zsh", "su", "env", "xargs", "exec")

    fun classify(command: String, cwd: File): CommandAssessment {
        val parsed = try { CommandTokenizer.parse(command) } catch (failure: CommandParseException) {
            return CommandAssessment(RiskLevel.SENSITIVE, exactPattern(command), command.trim(), cwd, emptyList(), failure.message)
        }
        var risk = RiskLevel.SAFE
        var blocked: String? = null
        parsed.forEach { part ->
            val executable = part.executable.substringAfterLast('/')
            val partRisk = when {
                executable in wrappers -> RiskLevel.SENSITIVE.also { blocked = "Nested shell/execution wrapper '$executable' is not allowed for agent commands" }
                executable == "git" -> classifyGit(part.words.drop(1))
                executable == "find" -> classifyFind(part.words.drop(1)).also { if (part.words.any { it == "-exec" || it == "-execdir" }) blocked = "find -exec is not allowed for agent commands" }
                executable == "sed" -> if (part.words.any { it == "-i" || it.startsWith("-i") }) RiskLevel.MODIFY else RiskLevel.SAFE
                executable in safe -> RiskLevel.SAFE
                executable in modify -> RiskLevel.MODIFY
                executable in destructive -> RiskLevel.DESTRUCTIVE
                executable in external -> RiskLevel.EXTERNAL
                executable.startsWith("./") || '/' in executable -> RiskLevel.MODIFY
                else -> RiskLevel.SENSITIVE
            }
            risk = maxRisk(risk, partRisk)
            if (part.words.any { it in setOf(">", ">>", "2>", "2>>") }) risk = maxRisk(risk, RiskLevel.MODIFY)
        }
        val pattern = if (parsed.size == 1) patternFor(parsed.single()) else exactPattern(command)
        return CommandAssessment(risk, pattern, normalize(parsed), cwd, parsed, blocked)
    }

    private fun classifyGit(args: List<String>): RiskLevel {
        val sub = args.firstOrNull { !it.startsWith('-') } ?: return RiskLevel.SAFE
        return when (sub) {
            "status", "diff", "log", "show", "branch", "rev-parse", "ls-files" -> RiskLevel.SAFE
            "add", "commit", "checkout", "switch", "init", "tag" -> RiskLevel.MODIFY
            "restore" -> if (args.contains("--staged") && args.none { it == "--worktree" }) RiskLevel.MODIFY else RiskLevel.DESTRUCTIVE
            "reset", "clean", "rebase" -> RiskLevel.DESTRUCTIVE
            "fetch", "pull", "push", "clone", "remote" -> RiskLevel.EXTERNAL
            else -> RiskLevel.SENSITIVE
        }
    }

    private fun classifyFind(args: List<String>): RiskLevel = when {
        args.contains("-delete") -> RiskLevel.DESTRUCTIVE
        args.any { it == "-exec" || it == "-execdir" } -> RiskLevel.SENSITIVE
        else -> RiskLevel.SAFE
    }

    private fun patternFor(command: ParsedCommand): String {
        val executable = command.executable.substringAfterLast('/')
        if (executable == "git") {
            val sub = command.words.drop(1).firstOrNull { !it.startsWith('-') }
            if (sub != null) return if (command.words.size > 2) "git $sub *" else "git $sub"
        }
        return if (command.words.size > 1) "$executable *" else executable
    }

    private fun exactPattern(command: String): String = "exact:${sha256(CommandRedactor.redact(command)).take(20)}"

    private fun normalize(commands: List<ParsedCommand>): String = commands.joinToString(" ; ") { part -> part.words.joinToString(" ") }

    private fun maxRisk(a: RiskLevel, b: RiskLevel): RiskLevel = if (rank(a) >= rank(b)) a else b
    private fun rank(risk: RiskLevel) = when (risk) {
        RiskLevel.SAFE -> 0
        RiskLevel.MODIFY -> 1
        RiskLevel.EXTERNAL -> 2
        RiskLevel.SENSITIVE -> 3
        RiskLevel.DESTRUCTIVE -> 4
    }
}

class CommandPolicy(private val classifier: CommandClassifier = CommandClassifier()) {
    fun assess(command: String, workspaceRoot: File, cwd: String = "."): CommandAssessment {
        val root = workspaceRoot.canonicalFile
        val resolvedCwd = resolveWithin(root, cwd) ?: return blocked(command, root, "Working directory escapes the workspace")
        if (!resolvedCwd.exists() || !resolvedCwd.isDirectory) return blocked(command, resolvedCwd, "Working directory does not exist")
        if (command.contains("$(") || command.contains('`')) return blocked(command, resolvedCwd, "Command substitution is not allowed for agent commands")
        val unsafeDollar = Regex("\\$(?!HOME(?:\\b|/)|PWD(?:\\b|/)|\\?)").containsMatchIn(command)
        if (unsafeDollar) return blocked(command, resolvedCwd, "Uncontrolled shell variable expansion is not allowed")

        val assessment = classifier.classify(command, resolvedCwd)
        if (!assessment.allowed) return assessment
        for (part in assessment.commands) {
            if (part.executable.substringAfterLast('/') in setOf("sh", "bash", "dash", "zsh", "su", "env", "xargs", "exec")) {
                return assessment.copy(blockedReason = "Execution wrappers are not allowed for agent commands")
            }
            for (raw in part.words.drop(1)) {
                if (raw in setOf(">", ">>", "2>", "2>>", "<")) continue
                val value = raw.substringAfter('=', raw)
                if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("ssh://")) continue
                if (containsParentSegment(value)) return assessment.copy(blockedReason = "Path traversal is not allowed: $value")
                if (File(value).isAbsolute) return assessment.copy(blockedReason = "Absolute paths are not allowed: $value")
                if (looksLikePath(value)) {
                    val target = File(resolvedCwd, value).canonicalFile
                    if (!isInside(root, target)) return assessment.copy(blockedReason = "Path escapes workspace: $value")
                }
            }
        }
        return assessment
    }

    fun resolveCwd(workspaceRoot: File, cwd: String): File = resolveWithin(workspaceRoot.canonicalFile, cwd)
        ?: throw SecurityException("Working directory escapes workspace")

    private fun blocked(command: String, cwd: File, reason: String) = CommandAssessment(
        RiskLevel.SENSITIVE, "exact:${sha256(CommandRedactor.redact(command)).take(20)}", CommandRedactor.redact(command), cwd, emptyList(), reason
    )

    private fun resolveWithin(root: File, path: String): File? {
        if (path.isBlank() || path == ".") return root
        if (File(path).isAbsolute || containsParentSegment(path)) return null
        val target = File(root, path).canonicalFile
        return target.takeIf { isInside(root, it) }
    }

    private fun isInside(root: File, child: File): Boolean = child == root || child.path.startsWith(root.path + File.separator)
    private fun containsParentSegment(value: String): Boolean = value.replace('\\', '/').split('/').any { it == ".." }
    private fun looksLikePath(value: String): Boolean = value == "." || value.startsWith("./") || '/' in value || '\\' in value || value.startsWith(".")
}

object CommandRedactor {
    private val optionSecret = Regex("(?i)(--?(?:password|passwd|token|api[-_]?key|secret|authorization)(?:=|\\s+))([^\\s]+)")
    private val envSecret = Regex("(?i)\\b([A-Z0-9_]*(?:TOKEN|PASSWORD|PASSWD|SECRET|API_KEY|APIKEY|AUTHORIZATION)[A-Z0-9_]*=)([^\\s]+)")
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+\\-/=]+")
    private val urlCredentials = Regex("(https?://[^:/\\s]+:)([^@/\\s]+)(@)")

    fun redact(command: String): String = command
        .replace(optionSecret) { "${it.groupValues[1]}***" }
        .replace(envSecret) { "${it.groupValues[1]}***" }
        .replace(bearer) { "${it.groupValues[1]}***" }
        .replace(urlCredentials) { "${it.groupValues[1]}***${it.groupValues[3]}" }
}

object LogRedactor {
    private val genericSecrets = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*)([^\\s]+)"),
        Regex("(?i)((?:api[-_]?key|token|password|passwd|secret)\\s*[:=]\\s*)([^\\s]+)")
    )

    fun redact(text: String): String {
        var value = CommandRedactor.redact(text)
        genericSecrets.forEach { pattern -> value = value.replace(pattern) { "${it.groupValues[1]}***" } }
        return value
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
