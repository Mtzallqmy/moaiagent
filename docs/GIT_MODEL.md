# Git Model

## Abstraction

All Git behavior is behind `GitEngine`. Phase 3 ships `JGitEngine`, an embedded pure-Java backend, so Git features work on Android even when a system `git` executable is not installed. A future CLI or libgit2 backend can implement the same interface without changing Agent tools or Compose screens.

`GitEngine` supports:

- repository detection and initialization
- status
- working-tree or staged diff
- log
- local branches
- checkout/create branch
- add/stage
- commit
- restore/unstage

Push, pull, remote authentication, force push, reset-hard, and clean are deliberately not exposed as Phase 3 Git tools.

## Workspace boundary

JGit opens only `<workspace>/.git`; it does not search parent directories. Every file path is validated as workspace-relative and canonicalized before use. Absolute paths and `..` traversal are rejected.

## Agent tools and risk

Read-only tools are available in Plan and Agent modes:

- `git_status`
- `git_diff`
- `git_log`
- `git_branches`

Agent-only modifying tools:

- `git_init` — MODIFY
- `git_checkout` — MODIFY
- `git_add` — MODIFY
- `git_commit` — MODIFY
- `git_restore` — MODIFY when unstaging, DESTRUCTIVE when restoring the worktree

All modifying/destructive Agent calls pass through PermissionEngine. Git tools never invoke arbitrary shell fragments.

## UI

The workspace Git screen displays current branch and status groups for staged, modified, deleted, untracked, and conflicting paths. It can show a bounded diff, stage, unstage, restore with destructive confirmation, and create a commit from a validated visible commit message. The user performs these UI actions directly; Agent-proposed Git actions remain governed by the Agent permission path.

## Commit validation

Commit messages are trimmed, must be non-empty, cannot contain NUL, and are capped at 5,000 characters. An explicit author can be supplied through the engine; otherwise JGit repository config is used, falling back to the local AgentDroid identity. No credentials are required for local commits.
