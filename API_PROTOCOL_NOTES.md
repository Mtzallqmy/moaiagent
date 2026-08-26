# Verified API protocol notes

These notes were collected from the official documentation pages during implementation.

## Anthropic Messages streaming

Anthropic Messages streaming uses server-sent events. The documented event sequence includes `message_start`, content blocks with `content_block_start`, one or more `content_block_delta`, `content_block_stop`, then `message_delta`, and `message_stop`; `ping` and `error` events may also occur. Text arrives in `content_block_delta` events with a `delta` object whose type is `text_delta` and whose `text` field contains the incremental text. Unknown event types must be ignored gracefully.

Reference: https://platform.claude.com/docs/en/build-with-claude/streaming

## Gemini REST

Gemini text generation uses `POST https://generativelanguage.googleapis.com/v1beta/{model=models/*}:generateContent` for non-streaming and `POST https://generativelanguage.googleapis.com/v1beta/{model=models/*}:streamGenerateContent` for streaming. Requests use `contents[]` with nested `parts[]`; system instructions and generation configuration are separate request fields when needed. Streaming responses contain successive `GenerateContentResponse` JSON chunks, from which text parts and usage metadata can be extracted.

Reference: https://ai.google.dev/api/generate-content
