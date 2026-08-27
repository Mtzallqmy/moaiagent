# AgentDroid Permission Model

## Decisions

Permission policy resolves to `ALLOW`, `ASK`, or `DENY`.

Default risk policy:

- `SAFE` → `ALLOW`
- `MODIFY` → `ASK`
- `DESTRUCTIVE` → `ASK`
- `EXTERNAL` → `ASK`
- `SENSITIVE` → `ASK`

Plan mode independently blocks non-SAFE tools, even if a stored rule would otherwise allow them.

## Scopes

- `ONCE`: applies only to the pending request.
- `SESSION`: cached in memory for the current agent session and removed when the session finishes/cancels.
- `ALWAYS`: persisted in Room as `PermissionRule`.

Persistent rules may be global or workspace-specific. A workspace-specific matching rule has priority over a global rule; newer equally-specific rules win.

## Approval UI

For an `ASK` decision, the Compose permission dialog shows the tool, target path when applicable, reason, risk level, summary, and proposed diff. Choices are Allow once, Allow session, Always allow, and Deny.

The model cannot resolve its own permission request. Only the application-side `PermissionRequestCoordinator` receives the user decision.

## Storage and management

Only `ALWAYS` rules are persisted. Session rules are never written as persistent rules. The Settings/Permissions UI lists stored rules and lets the user remove them. Audit records include the resolved permission decision for tool activity.

## Separation of responsibilities

The Tool Registry owns tool lookup and mode enforcement. The Permission Engine owns authorization policy. Tools own their domain validation and preview. This avoids embedding permission `if` statements throughout feature code and keeps authorization outside provider protocol adapters.

## Direct user actions

User-initiated Workspace operations are explicit UI actions. They use normal confirmation for destructive actions and ChangeSet/revert infrastructure, but Agent permission prompts are reserved for agent-initiated operations.
