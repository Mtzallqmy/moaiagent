# AgentDroid Tool Model

## Provider-neutral contract

Every tool implements `AgentTool` and exposes a `ToolDefinition` containing `name`, `description`, JSON `inputSchema`, `riskLevel`, and `category`. Execution receives normalized JSON input plus `ToolContext`; it returns a normalized `ToolResult`.

Provider adapters translate this contract to OpenAI/OpenRouter/OpenAI-compatible tools, Anthropic `tool_use`, and Gemini function declarations. Provider protocol objects do not enter `core/agent`.

## Central registry

`ToolRegistry` is the only lookup/execution catalog. It supports registration, lookup, listing, mode filtering, preview, and execution. Feature code must not distribute string-based `if (toolName == ...)` dispatch.

Mode rules are enforced twice: tool definitions are filtered before the model sees them, and registry execution rejects a call that is not allowed for the active mode.

## Phase 2 tools

| Tool | Category | Risk | Behavior |
| --- | --- | --- | --- |
| `read_file` | file read | SAFE | UTF-8 line-range read; binary returns metadata |
| `list_files` | file read | SAFE | directory listing with metadata |
| `search_files` | file search | SAFE | filename/text search, case option, glob, max results |
| `file_info` | file read | SAFE | size/type/time/MIME/binary/hash metadata |
| `write_file` | file modify | MODIFY | staged create/replace; overwrite is explicit |
| `patch_file` | file modify | MODIFY | staged exact/range/unified-diff patch with stale detection |
| `move_file` | file modify | MODIFY | staged move/rename with destination conflict checks |
| `delete_file` | file destructive | DESTRUCTIVE | staged deletion to workspace trash |
| `create_directory` | workspace | MODIFY | staged directory creation |

No process, terminal, package-install, browser-automation, or Git-command tool is implemented in Phase 2.

## Tool calls and streaming

The normalized `ToolCall` contains an ID, tool name, parsed JSON arguments, and optionally raw streamed arguments. `AiStreamEvent` represents tool-call start, argument deltas, and completion where the provider supports streaming arguments.

Malformed or incomplete arguments become structured provider/validation failures; they are not forwarded to platform APIs.

## Result injection

After execution, AgentLoop appends a `TOOL` transcript message containing success/error status, a bounded summary/output, error code if applicable, truncation state, and ChangeSet ID when produced. `ProviderAgentModelClient` converts that message back to the provider-native tool-result representation for the next model turn.

## Mutating tool lifecycle

Mutating tools use preview + staging:

1. validate workspace path and current file state;
2. build `ToolPreview` and proposed `FileChange`;
3. obtain permission when policy requires it;
4. revalidate the original hash/fingerprint after approval;
5. create a `PROPOSED` ChangeSet;
6. apply only when accepted or policy/UI explicitly commits it.

This prevents a stale patch generated against an older file from being silently applied.

## Errors

Tool failures use structured `AgentErrorCode` values including `TOOL_NOT_FOUND`, `TOOL_VALIDATION_ERROR`, `PERMISSION_DENIED`, `WORKSPACE_VIOLATION`, `PATCH_CONFLICT`, `FILE_TOO_LARGE`, `BINARY_FILE_UNSUPPORTED`, and `IO_ERROR`. Agent-level bounds use dedicated turn/tool-call/timeout/failure-limit errors.
