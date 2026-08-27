package com.agentdroid.core.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ResearchHttpLimits(
    val callTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 10_000,
    val maxBodyBytes: Long = 512_000,
    val maxRedirects: Int = 4
) {
    init {
        require(callTimeoutMs > 0 && readTimeoutMs > 0 && maxBodyBytes > 0)
        require(maxRedirects in 0..10)
    }
}

/** A bounded source fetcher. It never executes scripts and returns only extracted title/text. */
class OkHttpResearchSourceFetcher(
    client: OkHttpClient = OkHttpClient(),
    private val limits: ResearchHttpLimits = ResearchHttpLimits()
) : ResearchSourceFetcher {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(limits.callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(limits.readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun fetch(url: String, maxChars: Int): FetchedResearchPage = withContext(Dispatchers.IO) {
        require(maxChars > 0) { "maxChars must be positive" }
        var currentUrl = validateHttpUrl(url)
        var redirects = 0
        while (true) {
            val response = execute(currentUrl)
            val redirectedUrl = response.use {
                if (it.isRedirect) {
                    if (redirects >= limits.maxRedirects) throw ResearchSourceUnavailable(currentUrl, IOException("Too many redirects"))
                    val location = it.header("Location") ?: throw ResearchSourceUnavailable(currentUrl, IOException("Redirect has no Location"))
                    val resolved = if (SCHEME_PREFIX.containsMatchIn(location.trim())) {
                        location.trim()
                    } else {
                        runCatching { currentUrl.toHttpUrl().resolve(location)?.toString() }.getOrNull()
                            ?: throw ResearchSourceUnavailable(currentUrl, IOException("Invalid redirect URL"))
                    }
                    validateHttpUrl(resolved)
                } else {
                    if (!it.isSuccessful) throw ResearchSourceUnavailable(currentUrl, IOException("HTTP ${it.code}"))
                    val contentType = it.body?.contentType()?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    if (!isReadableContentType(contentType)) {
                        throw ResearchSourceUnavailable(currentUrl, IOException("Unsupported content type: ${contentType.ifBlank { "unknown" }}"))
                    }
                    val charByteLimit = maxChars.toLong().coerceAtMost(Int.MAX_VALUE.toLong()) * 4L
                    val byteLimit = minOf(limits.maxBodyBytes, charByteLimit.coerceAtLeast(1L))
                    val raw = readBoundedUtf8(it, byteLimit)
                    val html = contentType.contains("html") || looksLikeHtml(raw)
                    val title = if (html) HtmlTextExtractor.title(raw) else ""
                    val text = (if (html) HtmlTextExtractor.text(raw) else raw).take(maxChars)
                    if (text.isBlank()) throw ResearchSourceUnavailable(currentUrl, IOException("Source contained no readable text"))
                    return@withContext FetchedResearchPage(currentUrl, title, text, System.currentTimeMillis())
                }
            }
            currentUrl = redirectedUrl
            redirects++
        }
        @Suppress("UNREACHABLE_CODE")
        throw ResearchSourceUnavailable(currentUrl)
    }

    private fun execute(url: String): Response = try {
        client.newCall(
            Request.Builder().url(url)
                .header("Accept", "text/html,text/plain,application/xhtml+xml;q=0.9")
                .header("User-Agent", "AgentDroid/1.0 ResearchFetcher")
                .get().build()
        ).execute()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        throw ResearchSourceUnavailable(url, error)
    }

    private fun readBoundedUtf8(response: Response, maxBytes: Long): String {
        val body = response.body ?: throw ResearchSourceUnavailable(response.request.url.toString(), IOException("Empty response body"))
        val declared = body.contentLength()
        if (declared > maxBytes) throw ResearchSourceUnavailable(response.request.url.toString(), IOException("Response exceeds $maxBytes bytes"))
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val remainingWithSentinel = maxBytes - total + 1L
            if (remainingWithSentinel <= 0L) throw ResearchSourceUnavailable(response.request.url.toString(), IOException("Response exceeds $maxBytes bytes"))
            val read = source.read(buffer, minOf(8_192L, remainingWithSentinel))
            if (read == -1L) break
            total += read
            if (total > maxBytes) throw ResearchSourceUnavailable(response.request.url.toString(), IOException("Response exceeds $maxBytes bytes"))
        }
        return buffer.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    private companion object {
        val SCHEME_PREFIX = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    }
}

/** DuckDuckGo Instant Answer API implementation; this is one optional provider behind WebSearchProvider. */
class DuckDuckGoInstantAnswerProvider(
    client: OkHttpClient = OkHttpClient(),
    endpoint: String = "https://api.duckduckgo.com/",
    private val httpLimits: ResearchHttpLimits = ResearchHttpLimits(maxBodyBytes = 256_000),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : WebSearchProvider {
    private val endpoint = validateHttpUrl(endpoint).toHttpUrl()
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(httpLimits.callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(httpLimits.readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun search(query: String, limit: Int): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "Search query is required" }
        require(clean.length <= 1_000) { "Search query exceeds 1000 characters" }
        val boundedLimit = limit.coerceIn(1, 50)
        val url = endpoint.newBuilder()
            .addQueryParameter("q", clean)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "AgentDroid/1.0 ResearchSearch")
            .get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ResearchSourceUnavailable(url.toString(), error)
        }
        response.use {
            if (it.isRedirect) throw ResearchSourceUnavailable(url.toString(), IOException("Search provider redirect refused"))
            if (!it.isSuccessful) throw ResearchSourceUnavailable(url.toString(), IOException("Search provider HTTP ${it.code}"))
            val raw = readSearchBody(it, httpLimits.maxBodyBytes)
            val root = runCatching { json.parseToJsonElement(raw) as JsonObject }
                .getOrElse { error -> throw ResearchSourceUnavailable(url.toString(), error) }
            parseResults(root).asSequence()
                .filter { result -> runCatching { validateHttpUrl(result.url) }.isSuccess }
                .distinctBy { result -> result.url }
                .take(boundedLimit)
                .toList()
        }
    }

    private fun readSearchBody(response: Response, maxBytes: Long): String {
        val body = response.body ?: throw ResearchSourceUnavailable(response.request.url.toString(), IOException("Empty search response"))
        if (body.contentLength() > maxBytes) throw ResearchSourceUnavailable(response.request.url.toString(), IOException("Search response too large"))
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, minOf(8_192L, maxBytes - total + 1L))
            if (read == -1L) break
            total += read
            if (total > maxBytes) throw ResearchSourceUnavailable(response.request.url.toString(), IOException("Search response too large"))
        }
        return buffer.readString(Charsets.UTF_8)
    }

    private fun parseResults(root: JsonObject): List<WebSearchResult> {
        val output = mutableListOf<WebSearchResult>()
        val abstractUrl = root.string("AbstractURL")
        val abstractText = root.string("AbstractText")
        if (!abstractUrl.isNullOrBlank() && !abstractText.isNullOrBlank()) {
            output += WebSearchResult(
                abstractUrl,
                root.string("Heading").orEmpty().ifBlank { abstractText.take(120) }.take(500),
                abstractText.take(8_000),
                1.0
            )
        }
        fun visit(element: JsonElement) {
            if (output.size >= MAX_PARSED_CANDIDATES) return
            when (element) {
                is JsonArray -> {
                    for (child in element) {
                        visit(child)
                        if (output.size >= MAX_PARSED_CANDIDATES) break
                    }
                }
                is JsonObject -> {
                    val url = element.string("FirstURL")
                    val text = element.string("Text")
                    if (!url.isNullOrBlank() && !text.isNullOrBlank()) {
                        output += WebSearchResult(url, text.substringBefore(" - ").take(500), text.take(8_000), 0.7)
                    }
                    element["Topics"]?.let(::visit)
                }
                else -> Unit
            }
        }
        root["RelatedTopics"]?.let(::visit)
        return output
    }

    private companion object {
        const val MAX_PARSED_CANDIDATES = 200
    }
}

internal object HtmlTextExtractor {
    private val titleRegex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val discardedRegex = Regex("<(script|style|noscript|template)[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val breakRegex = Regex("</?(p|div|section|article|main|header|footer|li|tr|h[1-6]|br)[^>]*>", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("<[^>]+>")

    fun title(html: String): String = decode(tagRegex.replace(titleRegex.find(html)?.groupValues?.get(1).orEmpty(), " "))
        .replace(Regex("\\s+"), " ").trim().take(500)

    fun text(html: String): String = decode(
        tagRegex.replace(breakRegex.replace(discardedRegex.replace(html, " "), "\n"), " ")
    ).replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" *\\n+ *"), "\n")
        .trim()

    private fun decode(value: String): String = value
        .replace(Regex("&#(x[0-9a-f]+|\\d+);", RegexOption.IGNORE_CASE)) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x", true)) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            code?.takeIf { it in 0..0x10ffff }?.let { valid -> Character.toChars(valid).concatToString() } ?: match.value
        }
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun isReadableContentType(type: String): Boolean = type.isBlank() || type.startsWith("text/") || type.contains("application/xhtml+xml")
private fun looksLikeHtml(value: String): Boolean = value.trimStart().startsWith("<!doctype", true) || value.trimStart().startsWith("<html", true)
