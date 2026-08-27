# Browser Architecture

## Scope

Phase 4 introduces `core:browser` as the only boundary through which the agent can inspect or interact with web content. Android `WebView` remains a UI/runtime detail; it is never placed in model context and is not exposed by `ToolRegistry`.

## Main contracts

- `BrowserEngine` creates, finds, lists, and closes browser sessions.
- `BrowserSession` owns tabs, navigation history, structured page extraction, form filling, scrolling, screenshots, and lifecycle.
- `BrowserTool<I, O>` describes a typed operation, risk, permission class, and read-only status.
- `BrowserSurfaceProvider` is a UI-only capability that exposes an Android `View`. It is deliberately separate from model-facing services.

`WebViewBrowserEngine` implements both the engine and UI surface provider. Each session owns one or more isolated WebViews and publishes `StateFlow` values for session metadata and the active page state.

## Sessions and tabs

`BrowserSessionMetadata` contains only session/workspace/conversation IDs, bounded tab metadata, the active tab, current URL, and last-use timestamp. Tabs can be created, selected, and closed. A restored tab is marked as needing reload; the application does not pretend a live WebView survived process death.

`RoomBrowserMetadataStore` stores this metadata transactionally. Cookies, headers, form values, page bodies, and authentication material are not stored in Room.

## Structured page access

WebView may execute fixed, application-owned DOM adapters internally because modern pages require JavaScript. No arbitrary script argument or JavaScript bridge exists in the public contracts. Extracted elements are bounded and represented by `elementId`, `tag`, `text`, `role`, `ariaLabel`, `href`, `inputType`, `visible`, and `enabled`.

Generated IDs use the restricted `ad-<number>` form. Click and fill operations re-read the target and act by `elementId`; selectors and script fragments supplied by the model are rejected.

## Tools

The module implements navigation, page text/title/URL, find, click, fill, scroll, back, forward, reload, stop, screenshot, links, forms, and accessibility-tree operations. Agent adapters expose `browser_navigate`, `browser_read`, `browser_find`, `browser_click`, `browser_fill`, `browser_scroll`, `browser_back`, `browser_forward`, `browser_reload`, and `browser_screenshot`.

Page text, elements, field values, URLs, and scroll amounts have explicit limits. A screenshot returns a small `BrowserScreenshotReference`; PNG bytes are not inserted into model context. The default sink stores the image in application cache, while a workspace artifact sink can replace it through `BrowserScreenshotSink`.

## UI and recovery

`Phase4BrowserScreen` renders title, URL, progress, URL field, back/forward, refresh/stop, tabs, external-open action, and an agent-session link. It embeds only the `BrowserSurface` selected by the UI controller.

Failures are typed (`Navigation`, `UnsafeUrl`, `ElementNotFound`, `ElementNotInteractable`, `FormSubmissionDenied`, screenshot/session/tab errors). Callers can refresh page state and regenerate elements before changing the plan. No automatic unbounded click retry exists.

Application navigation and end-to-end controller wiring must be confirmed by Phase 4 build and UI verification before the phase is marked complete.

## Tests

Unit tests cover URL policy, element IDs, sensitive fields, risk escalation, and tool descriptors. An Android test uses `MockWebServer` and a local page for navigate/read/find/fill/click/history, and verifies unsafe navigation never reaches WebView. Public sites are not required in CI.
