package com.agentdroid.core.browser

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewBrowserEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: WebViewBrowserEngine

    @Before fun setUp() {
        server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""
            <html><head><title>Local start</title></head><body>
              <h1>Browser integration page</h1>
              <a href="/second">Open second</a>
              <form action="/submit" method="post">
                <label>Name <input name="name" aria-label="Name"></label>
                <label>Password <input name="password" type="password" aria-label="Password"></label>
                <button type="submit">Sign in</button>
              </form>
              <div style="height:1600px">scroll area</div>
            </body></html>
        """.trimIndent()))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><head><title>Second</title></head><body>Second page</body></html>"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><head><title>Second</title></head><body>Second page</body></html>"))
        server.start()
        engine = WebViewBrowserEngine(ApplicationProvider.getApplicationContext<Context>())
    }

    @After fun tearDown() = runBlocking {
        engine.sessions().map { it.metadata.value.sessionId }.forEach { engine.closeSession(it) }
        server.shutdown()
    }

    @Test fun navigateReadFindFillClickAndHistoryAgainstLocalPage(): Unit = runBlocking {
        val session = engine.createSession(BrowserSessionRequest("workspace", "conversation"))
        val view = requireNotNull(engine.surface(session.metadata.value.sessionId)).view
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1600)
        }

        session.navigate(server.url("/").toString())
        assertEquals("Local start", session.getPageTitle())
        assertTrue(session.getPageText().contains("Browser integration page"))
        assertEquals(1, session.findText("integration").size)
        val screenshot = session.takeScreenshot()
        assertEquals("image/png", screenshot.mimeType)
        assertTrue(screenshot.referenceId.startsWith("browser-screenshot:"))

        val form = session.getForms().single()
        assertEquals("post", form.method)
        assertEquals(1, form.sensitiveFieldIds.size)
        val name = form.fields.first { it.ariaLabel == "Name" }
        assertTrue(session.fillField(name.elementId, "Ada").performed)

        val submit = session.elements().first { it.text == "Sign in" }
        val denied = runCatching { session.clickElement(submit.elementId) }.exceptionOrNull() as BrowserException
        assertTrue(denied.error is BrowserError.FormSubmissionDenied)

        val link = session.getLinks().single()
        assertTrue(session.clickElement(link.elementId).performed)
        withTimeout(10_000) {
            while (!session.getCurrentUrl().endsWith("/second") || session.getPageTitle() != "Second") delay(25)
        }
        assertEquals("Second", session.getPageTitle())
        assertTrue(session.goBack().currentUrl.orEmpty().endsWith("/"))
        assertTrue(session.goForward().currentUrl.orEmpty().endsWith("/second"))
        assertTrue(session.reloadPage().currentUrl.orEmpty().endsWith("/second"))
        assertTrue(session.scrollPage(ScrollDirection.DOWN, 100).performed)
        assertFalse(session.metadata.value.tabs.isEmpty())
    }

    @Test fun unsafeNavigationNeverReachesWebView(): Unit = runBlocking {
        val session = engine.createSession(BrowserSessionRequest("workspace", "conversation"))
        val failure = runCatching { session.navigate("file:///sdcard/secret") }.exceptionOrNull() as BrowserException
        assertTrue(failure.error is BrowserError.UnsafeUrl)
        assertEquals("", session.getCurrentUrl())
    }

    @Test fun createsSwitchesAndClosesIndependentTabs(): Unit = runBlocking {
        val session = engine.createSession(BrowserSessionRequest("workspace", "conversation"))
        val firstId = session.metadata.value.activeTabId
        val firstSurface = requireNotNull(engine.surface(session.metadata.value.sessionId, firstId)).view
        session.navigate(server.url("/").toString())

        val second = session.createTab(server.url("/second").toString(), activate = true)
        val secondSurface = requireNotNull(engine.surface(session.metadata.value.sessionId, second.tabId)).view
        assertFalse(firstSurface === secondSurface)
        assertEquals(2, session.metadata.value.tabs.size)
        assertEquals(second.tabId, session.metadata.value.activeTabId)
        assertEquals("Second", session.getPageTitle())

        session.switchTab(firstId)
        assertEquals("Local start", session.getPageTitle())
        assertTrue(session.getPageText().contains("Browser integration page"))
        session.switchTab(second.tabId)
        assertTrue(session.getPageText().contains("Second page"))

        assertTrue(session.closeTab(firstId))
        assertEquals(listOf(second.tabId), session.metadata.value.tabs.map { it.tabId })
        val lastTabFailure = runCatching { session.closeTab(second.tabId) }.exceptionOrNull() as BrowserException
        assertTrue(lastTabFailure.error is BrowserError.LastTab)
    }

    @Test fun formSubmitNeedsBoundOneTimeApproval(): Unit = runBlocking {
        val session = engine.createSession(BrowserSessionRequest("workspace", "conversation"))
        val view = requireNotNull(engine.surface(session.metadata.value.sessionId)).view
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1600)
        }
        session.navigate(server.url("/").toString())
        val form = session.getForms().single()
        val submit = session.elements().first { it.inputType == "submit" }
        val wrong = FormSubmissionApproval(submit.elementId, "wrong.example", form.action)
        val denied = runCatching { session.submitForm(submit.elementId, wrong) }.exceptionOrNull() as BrowserException
        assertTrue(denied.error is BrowserError.FormSubmissionDenied)

        val domain = URI(session.getCurrentUrl()).host.orEmpty().lowercase()
        assertTrue(session.submitForm(submit.elementId, FormSubmissionApproval(submit.elementId, domain, form.action)).performed)
        assertEquals("/", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals("/submit", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test fun restoresOnlySafeTabMetadataAndMarksItForReload(): Unit = runBlocking {
        val safe = BrowserTabMetadata("safe-tab", "Saved", server.url("/saved").toString())
        val unsafe = BrowserTabMetadata("unsafe-tab", "Unsafe", "file:///sdcard/token")
        val session = engine.createSession(
            BrowserSessionRequest(
                workspaceId = "workspace",
                conversationId = "conversation",
                restoredTabs = listOf(safe, unsafe),
                restoredActiveTabId = "safe-tab"
            )
        )
        assertEquals(2, session.metadata.value.tabs.size)
        assertTrue(session.metadata.value.tabs.first { it.tabId == "safe-tab" }.needsReload)
        assertEquals(null, session.metadata.value.tabs.first { it.tabId == "unsafe-tab" }.currentUrl)
        session.switchTab("unsafe-tab")
        assertEquals("", session.getCurrentUrl())
    }
}
