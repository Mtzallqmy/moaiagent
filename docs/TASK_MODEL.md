# Task Model

## Purpose

`core:tasks` represents long, multi-step work without exposing model reasoning. A `TaskPlan` contains only a concise summary and user-visible steps such as Search, Open sources, Extract, Compare, Create report, and Save artifact.

## Data model

- `Task`: identity, workspace/conversation scope, plan, status, progress, current step, artifacts, timestamps, failure/recovery state, and optimistic revision.
- `TaskPlan`: summary, ordered `TaskStep` list, plan revision, and update time.
- `TaskStep`: title/description, position, status, retry counter, timestamps, and error.
- `TaskEvent`: durable event type, step, timestamp, message, and task revision.
- `ArtifactRef`: small output reference; artifact content is not duplicated.

Statuses are `PENDING`, `RUNNING`, `WAITING_PERMISSION`, `WAITING_USER`, `COMPLETED`, `FAILED`, and `CANCELLED`. Wait reasons distinguish permission, user input, pause, and recovery-required states.

## State ownership

`TaskStateMachine` owns transitions and derived progress. `TaskEngine` scopes lookup/mutation to a workspace and delegates persistence to `TaskRepository`. Tool inputs choose validated actions; they cannot assign an arbitrary status or percentage. Optimistic revisions prevent stale writers from overwriting newer state.

The engine supports create/start, complete/fail step, wait for permission/user, pause/resume, cancel, retry, revise remaining plan, attach artifact, list/get, and event history. Retry counts and per-step maxima are enforced.

## Agent tools

The registry adapters are `create_task`, `update_task`, `complete_task_step`, `fail_task_step`, `list_tasks`, and `get_task`. `update_task` accepts a closed set of state-machine actions. Completion and progress are calculated from step state.

## Persistence and recovery

Room schema version 4 stores tasks, ordered steps, and events. `RoomTaskPersistence` writes a complete record transactionally. On startup, interrupted `RUNNING` or `WAITING_PERMISSION` work becomes `WAITING_USER` with `RECOVERY_REQUIRED`; running steps become retryable and a recovery event is appended. No dead OS operation is reported as running.

Default limits include 100 active tasks per workspace, 50 steps per task, a 240-character title, and bounded event messages.

## UI and tests

`Phase4TasksScreen` provides list/detail views with status, progress, current step, timestamps/duration, workspace, conversation, and artifacts. It exposes pause, cancel, retry, open conversation, and open artifact callbacks when compatible with state. Root-route and live repository wiring require final verification.

Tests exercise transitions, progress, cancellation, waiting, retry limits, plan revision, persistence/recovery, and a ToolRegistry flow that creates a multi-step task, advances it, attaches an artifact, and completes it.
