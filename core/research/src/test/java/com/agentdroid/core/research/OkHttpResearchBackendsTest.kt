package com.agentdroid.core.research

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpResearchBackendsTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `fetcher follows bounded safe redirect and extracts readable html`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/article"))
        server.enqueue(
            MockResponse().addHeader("Content-Type", "text/html; charset=utf-8").setBody(
                """
                <html><head><title>  Android &amp; Markdown </title><style>.hidden{}</style></head>
                <body><h1>Libraries</h1><p>Useful &lt;comparison&gt;.</p><script>secretToken()</script></body></html>
                """.trimIndent()
            )
        )
        val fetcher = OkHttpResearchSourceFetcher(OkHttpClient(), ResearchHttpLimits(maxRedirects = 2))
        val page = fetcher.fetch(server.url("/start").toString(), 1_000)

        assertEquals(server.url("/article").toString(), page.finalUrl)
        assertEquals("Android & Markdown", page.title)
        assertTrue(page.text.contains("Libraries"))
        assertTrue(page.text.contains("Useful <comparison>."))
        assertFalse(page.text.contains("secretToken"))
        assertFalse(page.text.contains(".hidden"))
        assertEquals(2, server.requestCount)
    }

    @Test(expected = UnsafeResearchUrl::class)
    fun `fetcher rejects unsafe scheme before request`(): Unit = runBlocking {
        OkHttpResearchSourceFetcher().fetch("file:///data/data/secret", 100)
    }

    @Test(expected = UnsafeResearchUrl::class)
    fun `fetcher rejects unsafe redirect target`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "file:///data/data/secret"))
        OkHttpResearchSourceFetcher().fetch(server.url("/redirect").toString(), 100)
    }

    @Test(expected = ResearchSourceUnavailable::class)
    fun `fetcher enforces redirect budget`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/two"))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/three"))
        val fetcher = OkHttpResearchSourceFetcher(OkHttpClient(), ResearchHttpLimits(maxRedirects = 1))
        fetcher.fetch(server.url("/one").toString(), 100)
    }

    @Test(expected = ResearchSourceUnavailable::class)
    fun `fetcher rejects response exceeding byte budget`(): Unit = runBlocking {
        server.enqueue(MockResponse().addHeader("Content-Type", "text/plain").setBody("x".repeat(101)))
        val fetcher = OkHttpResearchSourceFetcher(OkHttpClient(), ResearchHttpLimits(maxBodyBytes = 100))
        fetcher.fetch(server.url("/large").toString(), 1_000)
    }

    @Test(expected = ResearchSourceUnavailable::class)
    fun `fetcher rejects binary content`(): Unit = runBlocking {
        server.enqueue(MockResponse().addHeader("Content-Type", "application/octet-stream").setBody("binary"))
        OkHttpResearchSourceFetcher().fetch(server.url("/binary").toString(), 100)
    }

    @Test
    fun `duckduckgo provider parses abstract and nested related topics within limit`(): Unit = runBlocking {
        server.enqueue(
            MockResponse().addHeader("Content-Type", "application/json").setBody(
                """
                {
                  "Heading":"Markdown",
                  "AbstractText":"Markdown is a markup language.",
                  "AbstractURL":"https://example.com/markdown",
                  "RelatedTopics":[
                    {"FirstURL":"https://example.com/a","Text":"Library A - Android renderer"},
                    {"Name":"Group","Topics":[
                      {"FirstURL":"https://example.com/b","Text":"Library B - CommonMark renderer"}
                    ]}
                  ]
                }
                """.trimIndent()
            )
        )
        val provider = DuckDuckGoInstantAnswerProvider(OkHttpClient(), server.url("/").toString())
        val results = provider.search("android markdown", 2)

        assertEquals(2, results.size)
        assertEquals("https://example.com/markdown", results[0].url)
        assertEquals("Markdown", results[0].title)
        assertEquals("https://example.com/a", results[1].url)
        val request = server.takeRequest()
        assertEquals("android markdown", request.requestUrl?.queryParameter("q"))
        assertEquals("json", request.requestUrl?.queryParameter("format"))
    }

    @Test
    fun `duckduckgo provider discards unsafe and duplicate results`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"RelatedTopics":[
                {"FirstURL":"javascript:alert(1)","Text":"Unsafe"},
                {"FirstURL":"https://example.com/a","Text":"A"},
                {"FirstURL":"https://example.com/a","Text":"A duplicate"}
            ]}"""
        ))
        val provider = DuckDuckGoInstantAnswerProvider(OkHttpClient(), server.url("/").toString())
        val results = provider.search("query", 10)
        assertEquals(1, results.size)
        assertEquals("https://example.com/a", results.single().url)
    }

    @Test(expected = ResearchSourceUnavailable::class)
    fun `duckduckgo provider refuses redirects`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/elsewhere"))
        DuckDuckGoInstantAnswerProvider(OkHttpClient(), server.url("/").toString()).search("query", 3)
    }
}
