# Subagent Architecture

## Roles and isolation

`core:subagents` defines four initial roles:

| Role | Default capability |
| --- | --- |
| Coding | Read/analyze code, prepare changes, approved runtime/Git tools; no browser by default |
| Research | Search, collect sources, compare, summarize; no workspace write/delete tools |
| Browser | Navigate and inspect via structured browser tools; no arbitrary JavaScript |
| Review | Read-only review of code, research, artifacts, or task results |

Each `SubagentProfile` contains instructions, allowed tools/context sections, context-character limit, token limit, and tool-call limit. `ContextSubsetter` transmits only permitted sections and truncates to budget; it never forwards the full main-agent transcript.

## Delegation

`DefaultSubagentCoordinator` accepts a structured `SubagentTask`, creates the role profile, runs a bounded subagent, and returns `SubagentResult`. `delegate_task` adapts this coordinator to Agent Core with role, objective, and bounded context.

Nested delegation records root task, parent task, parent subagent, and depth. Tool calls pass through `SubagentToolGateway`, which checks the role whitelist and tool-call budget before the shared registry/permission system.

## Limits

Defaults are maximum 8 subagents per root task, 3 concurrently active subagents, delegation depth 2, task duration 120 seconds, 8,000 tokens, 24 tool calls, and one retry. All are injectable.

`SubagentLimitReached`, `DelegationDepthExceeded`, tool denial/budget, timeout, failure, and cancellation produce typed summarized results. Recursion cannot grow without bound.

## Recovery and skills

The default policy retries a recoverable failure within budget, then stops. An optional policy can fall back to another role while preventing role cycles. The main agent receives a failure summary, never internal reasoning.

`SkillRoleBindingRepository` can attach instructions to a role. Bindings cannot expand tools unless the coordinator explicitly enables `allowSkillToolExpansion`.

## Timeline and persistence

The coordinator exposes a `StateFlow<List<SubagentTimelineItem>>` containing only role, label, parent, status, times, and safe failure summary. `Phase4SubagentTimeline` renders this projection without prompts or chain-of-thought.

Room stores redacted delegation event projections. On restart, interrupted queued/running entries are marked failed; the application does not claim dead subagents are active.

## Tests

Tests cover Research-to-Review and nested delegation, retry/success, fallback, safe failures, total/concurrent/depth limits, role tool whitelists, tool budgets, context subsets, timeouts, skill restrictions, model adapters, and `delegate_task` serialization.
