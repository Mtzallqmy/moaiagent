# AgentDroid Agent Architecture

Phase 2 extends the stable Phase 1 Android application with a bounded, provider-neutral agent. It does not replace the existing provider, conversation, memory, skills, or settings foundations.

## Modules

- `core/agent`: `AgentLoop`, session/state/events, context management, tool contracts, registry, execution limits, audit contracts.
- `core/permissions`: permission policy and approval coordination.
- `core/workspace`: workspace-confined file system, file tools, diff engine, staged change sets.
- `core/ai`: provider adapters and tool-call normalization.
- `data/database`: Room persistence for workspace metadata, permission rules, audit records, and change sets.
- `app`: Compose UI, ViewModels, explorer/editor, diff review, permission UI, tool cards, and timeline.

`core/agent` does not depend on a provider protocol. Provider-specific messages are normalized by `ProviderAgentModelClient`.

## Loop

1. Create an `AgentSession` containing conversation, workspace, provider, model, and mode.
2. `ContextManager` builds bounded context from conversation history, workspace summary, selected file context, manual Memory, and enabled Skills.
3. The model receives only tool definitions allowed by the current mode.
4. Streamed provider tool calls normalize to `ToolCall`.
5. `ToolRegistry` resolves and validates the call.
6. Mutating tools prepare a preview.
7. `PermissionEngine` resolves the operation according to policy and scope.
8. The registry executes the tool.
9. The normalized `ToolResult` is appended to the model transcript.
10. The model continues until a final answer or an execution bound is reached.

Multiple tool calls in one model turn are supported and each result is reinjected.

## Modes

- **Chat:** existing streaming chat path; no automatic tools.
- **Plan:** agent loop with only `SAFE` tools. Mutating tools are neither advertised nor executable.
- **Agent:** registered tools are available subject to the Permission Engine.

Providers without tool-calling support keep Chat working; Plan and Agent are disabled with a visible warning.

## Bounds and recovery

`AgentConfig` includes `maxTurns`, `maxToolCalls`, `maxExecutionTimeMs`, `maxConsecutiveFailures`, `maxRepeatedFailureSignature`, context limits, and tool-result limits. Tool errors are reinjected so the model can recover using another approach, while repeated identical failures are bounded. Session permissions are cleared when the agent session ends or is cancelled.

## User-visible execution

Operational events are shown as tool cards and timeline steps: reading, searching, preparing a change, waiting for approval, staging/applying a change, or completion. Internal chain-of-thought is not displayed.

## Context strategy

Context is progressively disclosed. The application sends a compact workspace summary and selected/relevant file context instead of serializing the whole workspace. Manual global/workspace Memory and explicitly enabled Skills are injected into system context. Automatic memory creation and complex skill discovery remain outside Phase 2.

## Change staging

Agent mutations create a `ChangeSet` in `PROPOSED` state. The user can inspect the diff and Accept, Reject, or Edit it. Acceptance revalidates hashes/fingerprints before writing. Applied changes can be reverted while their expected current state still matches.

Direct user edits in the Workspace editor are explicit user actions; they still pass through the ChangeSet manager before commit but do not require Agent permission approval.

## References

OpenCode informed the separation between tool registration and permission policy. Hermes Agent informed the model-to-tools-to-results loop and centralized tool catalog. AgentDroid reimplements the applicable design in Kotlin for Android; no OpenCode or Hermes source files were copied.
