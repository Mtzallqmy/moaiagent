# Command Security

## Principle

A model-produced command is data, not authority. AgentDroid parses and classifies it before any process exists, then routes the resulting risk through PermissionEngine. Only `core:runtime` may instantiate `ProcessBuilder`.

## Parsing and classification

`CommandTokenizer` handles quoting, escaping, common shell operators, pipelines, and command sequences sufficiently to classify each executable segment rather than relying on a raw string prefix.

Default examples:

| Command family | Risk |
| --- | --- |
| `pwd`, `ls`, `cat`, `git status`, `git diff`, `git log` | SAFE |
| `mkdir`, `cp`, `mv`, `git add`, `git commit`, `git checkout`, `./workspace-tool` | MODIFY |
| `rm`, `rmdir`, `git reset`, `git clean`, worktree `git restore` | DESTRUCTIVE |
| `curl`, `wget`, `ssh`, `scp`, `git fetch/pull/push/clone` | EXTERNAL |
| unknown executables | SENSITIVE |

Nested execution wrappers such as `sh -c`, `bash -c`, `env`, `xargs`, and `exec` are blocked for model-produced commands rather than recursively guessing what they will execute. Command substitution using backticks or `$()` is blocked. Absolute executable paths are blocked. Workspace executables such as `./gradlew` are allowed only after canonical workspace enforcement and receive MODIFY risk.

## Workspace enforcement

`CommandPolicy` canonicalizes `cwd` under the workspace root. Absolute paths and any `..` component are rejected. Path-like arguments are canonicalized before execution. The shell is started by the trusted runtime layer; the model cannot select an arbitrary absolute shell executable.

This policy is intentionally stricter than an interactive user terminal. A user typing in Terminal is directly controlling the PTY; model execution always goes through the Agent tool path.

## Permissions

Dynamic shell tools provide a constrained permission identity such as:

- `run_command:git status`
- `run_command:git diff *`
- `run_command:rm *`
- `run_command:./gradlew *`

Only an exact key or one final argument wildcard is valid. A stored broad Phase 2 rule such as `*` or static `run_command` is not allowed to auto-authorize a dynamic command key. `ASK` rules explicitly prompt. User choices support allow once, allow for the Agent session, always allow the constrained pattern, or deny.

## Limits

Runtime limits cap stdout, stderr, timeout, maximum runtime, background process count, and concurrent process count. Cancellation kills an awaited foreground process; independent background processes remain alive until they exit, time out, or are stopped explicitly.

## Redaction and audit

`CommandRedactor` and `LogRedactor` remove common password/token/API-key/Authorization patterns and URL credentials before commands, process metadata, output, or audit records are persisted/displayed through Agent runtime cards.

Audit stores the redacted input summary, result summary, duration, permission decision, and structured redacted metadata including command/cwd/exit code/process id/timeout/Git action where applicable. API keys, bearer tokens, passwords, and authorization headers must never be intentionally written to audit storage.
