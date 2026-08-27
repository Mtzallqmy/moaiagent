# Browser Security

## Trust boundary

The model can call typed browser tools. It cannot receive a `WebView`, invoke `evaluateJavascript`, register a JavaScript interface, open an Android intent, or read cookies and HTTP authorization state. Fixed DOM scripts live entirely inside `WebViewBrowserEngine` and accept only validated, encoded values.

## URL policy

`BrowserUrlPolicy` applies one decision before navigation and again in WebView callbacks:

| Scheme | Default decision |
| --- | --- |
| `http`, `https` | Allowed when host exists and URL has no user-info credentials |
| `intent`, `tel`, `sms`, `market` | External action requiring explicit permission; not loaded in WebView |
| `file`, `content`, `javascript`, `data` | Denied |
| Unknown or missing scheme | Denied |

Control characters, excessive length, malformed syntax, and embedded credentials are rejected. Mixed content, file/content access, geolocation, script-opened windows, and automatic media playback are disabled.

## Element and form safety

- The agent selects elements only through generated `ad-<number>` IDs.
- The engine refreshes metadata and requires the target to be visible and enabled.
- Fill accepts only input, textarea, and select elements and never submits the form.
- Submit controls are rejected until a dedicated confirmed submit flow is supplied.
- Password, token, card, CVV/CVC, OTP/PIN, account, e-mail, and phone-like fields are sensitive. Their values are omitted from previews and logs.

`SensitiveBrowserFormPermissionDialog` displays domain, action, field IDs, and redacted sensitive fields. It offers **Allow once** and **Deny** only.

## Permission classification

| Operation | Default class |
| --- | --- |
| HTTP(S) navigation, read, find, scroll, history | SAFE |
| Ordinary in-page link click | SAFE after structured re-check |
| Fill field | MODIFY; sensitive fields escalate to SENSITIVE |
| Submit form | EXTERNAL and explicit confirmation |
| Download | EXTERNAL and disabled in the current engine |
| Open external application | EXTERNAL and explicit permission |

Dynamic assessment uses the actual URL or refreshed element, not only the tool name. External links cannot be clicked through the WebView tool path.

## Downloads, cookies, and secrets

The download listener refuses downloads and does not execute a file or silently hand it to another application. External-app schemes are never dispatched automatically.

WebView uses Android's normal cookie store. Cookies are neither returned by browser contracts nor persisted by AgentDroid. Browser metadata sanitization drops URL user-info, fragments, non-web schemes, and secret-like query parameters. Passwords, cookies, tokens, authorization headers, and complete sensitive values are prohibited from audit/context payloads.

Android Safe Browsing is enabled where supported. A renderer crash, stale page, or missing element produces a typed error. Recovery re-reads state/elements and revises the plan; it does not repeat clicks without limit.
