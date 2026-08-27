package com.agentdroid.core.subagents

object DefaultSubagentProfiles {
    val CODING = SubagentProfile(
        role = SubagentRole.CODING,
        instructions = "Analyze code, read relevant files, prepare focused patches, run approved commands, and inspect Git. Do not browse unless the task profile explicitly allows it.",
        allowedTools = setOf(
            "read_file", "read_file_range", "list_directory", "search_files", "find_files",
            "apply_patch", "write_file", "run_command", "process_status", "process_output",
            "git_status", "git_diff", "git_log", "git_branches", "git_add"
        ),
        allowedContext = setOf(
            ContextSection.TASK_SUMMARY, ContextSection.WORKSPACE_SUMMARY,
            ContextSection.SELECTED_FILES, ContextSection.GIT_DIFF, ContextSection.SUBAGENT_RESULTS
        ),
        maxContextCharacters = 24_000,
        tokenLimit = 8_000,
        toolCallLimit = 32
    )

    val RESEARCH = SubagentProfile(
        role = SubagentRole.RESEARCH,
        instructions = "Search the web, collect traceable sources, compare evidence, and return a concise cited summary. Do not write or delete workspace files.",
        allowedTools = setOf(
            "web_search", "research_start", "research_add_source", "research_extract",
            "research_compare", "research_finalize", "browser_navigate", "browser_read"
        ),
        allowedContext = setOf(
            ContextSection.TASK_SUMMARY, ContextSection.RESEARCH_FINDINGS,
            ContextSection.SOURCE_REFERENCES, ContextSection.SUBAGENT_RESULTS
        ),
        maxContextCharacters = 20_000,
        tokenLimit = 7_000,
        toolCallLimit = 24
    )

    val BROWSER = SubagentProfile(
        role = SubagentRole.BROWSER,
        instructions = "Navigate and inspect pages, interact only through structured element identifiers, and collect structured page data. Never execute arbitrary JavaScript.",
        allowedTools = setOf(
            "browser_navigate", "browser_read", "browser_find", "browser_click", "browser_fill",
            "browser_scroll", "browser_back", "browser_forward", "browser_screenshot"
        ),
        allowedContext = setOf(
            ContextSection.TASK_SUMMARY, ContextSection.BROWSER_STATE,
            ContextSection.SOURCE_REFERENCES
        ),
        maxContextCharacters = 16_000,
        tokenLimit = 6_000,
        toolCallLimit = 30
    )

    val REVIEW = SubagentProfile(
        role = SubagentRole.REVIEW,
        instructions = "Review code, research, artifacts, or task results. Report concrete issues and quality findings; remain read-only.",
        allowedTools = setOf(
            "read_file", "read_file_range", "list_directory", "search_files",
            "git_status", "git_diff", "read_artifact", "list_artifacts"
        ),
        allowedContext = setOf(
            ContextSection.TASK_SUMMARY, ContextSection.WORKSPACE_SUMMARY,
            ContextSection.SELECTED_FILES, ContextSection.GIT_DIFF,
            ContextSection.RESEARCH_FINDINGS, ContextSection.SOURCE_REFERENCES,
            ContextSection.ARTIFACT_REFERENCES, ContextSection.SUBAGENT_RESULTS
        ),
        maxContextCharacters = 24_000,
        tokenLimit = 6_000,
        toolCallLimit = 16
    )

    val all: Map<SubagentRole, SubagentProfile> = listOf(CODING, RESEARCH, BROWSER, REVIEW).associateBy { it.role }
}

fun interface SubagentProfileProvider {
    fun profile(role: SubagentRole): SubagentProfile
}

class DefaultSubagentProfileProvider(
    private val profiles: Map<SubagentRole, SubagentProfile> = DefaultSubagentProfiles.all
) : SubagentProfileProvider {
    override fun profile(role: SubagentRole): SubagentProfile =
        profiles[role] ?: error("No subagent profile configured for $role")
}

class ContextSubsetter {
    fun subset(context: SubagentContext, profile: SubagentProfile): SubagentContext {
        var remaining = profile.maxContextCharacters
        val filtered = linkedMapOf<ContextSection, String>()
        context.sections.forEach { (section, value) ->
            if (section !in profile.allowedContext || remaining <= 0) return@forEach
            val clipped = value.take(remaining)
            filtered[section] = clipped
            remaining -= clipped.length
        }
        return context.copy(sections = filtered)
    }
}

@kotlinx.serialization.Serializable
data class SkillRoleBinding(
    val skillId: String,
    val role: SubagentRole,
    val additionalInstructions: String? = null,
    val additionalAllowedTools: Set<String> = emptySet()
)

interface SkillRoleBindingRepository {
    suspend fun bindingsFor(role: SubagentRole): List<SkillRoleBinding>
}

class InMemorySkillRoleBindingRepository(
    bindings: Iterable<SkillRoleBinding> = emptyList()
) : SkillRoleBindingRepository {
    private val values = bindings.toList()
    override suspend fun bindingsFor(role: SubagentRole) = values.filter { it.role == role }
}
