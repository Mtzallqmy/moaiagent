# Task Model

## Purpose

`core:tasks` represents long, multi-step work without exposing model reasoning. A `TaskPlan` contains only a concise summary and user-visible execution steps.

Phase 5 adds a model-driven planning boundary before production Plan/Agent execution. The full planner result is schema-validated before it is converted into the existing ordered task state machine.

## Data model

- `Task`: identity, workspace/conversation scope, plan, status, progress, current step, artifacts, timestamps, failure/recovery state, and optimistic revision.
- `TaskPlan`: summary, ordered `TaskStep` list, plan revision, and update time.
- `TaskStep`: title/description, position, status, retry counter, timestamps, and error.
- `TaskEvent`: durable event type, step, timestamp, message, and task revision.
- `ArtifactRef`: small output reference; artifact content is not duplicated.
- `StructuredTaskPlan`: Phase 5 planning result with step IDs, goals, dependencies, expected capabilities, and acceptance criteria before conversion to execution steps.

Statuses are `PENDING`, `RUNNING`, `WAITING_PERMISSION`, `WAITING_USER`, `COMPLETED`, `FAILED`, and `CANCELLED`. Wait reasons distinguish permission, user input, pause, and recovery-required states.

## Planning and state ownership

`ModelDrivenPlanner` owns structured plan generation/validation. It detects cycles, enforces plan limits, rejects unadvertised capabilities, repairs one malformed/invalid model response, and retains a deterministic fallback planner.

`ModelPlanningCoordinator` is the application orchestration boundary. It builds planner context from actual workspace memory/skills, derives tool capabilities from the active `ToolRegistry`, adds only runtime capabilities that passed `RuntimeVerifier`, and persists a validated plan before `AgentLoop` starts. An existing active task is reused rather than duplicated.

`TaskStateMachine` owns transitions and derived progress. `TaskEngine` scopes lookup/mutation to a workspace and delegates persistence to `TaskRepository`. `TaskEngine.createFromPlan` accepts a plan that has already passed the external planner's validation. Tool inputs choose validated actions; they cannot assign an arbitrary status or percentage. Optimistic revisions prevent stale writers from overwriting newer state.

The execution engine supports create/start, complete/fail step, wait for permission/user, pause/resume, cancel, retry, revise remaining plan, attach artifact, list/get, and event history. Retry counts and per-step maxima are enforced.

## Agent tools

The registry adapters are `create_task`, `update_task`, `complete_task_step`, `fail_task_step`, `list_tasks`, and `get_task`. `update_task` accepts a closed set of state-machine actions. Completion and progress are calculated from step state.

The `create_task` tool remains available to the Agent, but the production Plan/Agent entry path no longer depends on a model manually supplying pre-authored step titles: a validated Phase 5 plan is created first.

## Persistence and recovery

Room schema version 4 stores tasks, ordered steps, and events. `RoomTaskPersistence` writes a complete record transactionally. On startup, interrupted `RUNNING` or `WAITING_PERMISSION` work becomes `WAITING_USER` with `RECOVERY_REQUIRED`; running steps become retryable and a recovery event is appended. No dead OS operation is reported as running.

The complete Phase 5 DAG exists in `StructuredTaskPlan` at the planning boundary and is topologically ordered into the current execution-oriented `TaskPlan`. Phase 5 intentionally does not add a database migration solely to duplicate dependency/capability metadata before a graph scheduler needs durable branch-level scheduling.

Default limits include 100 active tasks per workspace, 50 execution steps per task, a 240-character title, and bounded event messages. The model-driven planner applies its own smaller default maximum before conversion.

## UI and tests

Task UI provides list/detail views with status, progress, current step, timestamps/duration, workspace, conversation, and artifacts. Plan creation adds a concise timeline item describing whether the source was the model, a repaired model plan, or deterministic fallback; hidden reasoning is never displayed.

Tests exercise transitions, progress, cancellation, waiting, retry limits, plan revision, persistence/recovery, ToolRegistry task flows, model-plan differentiation, cycle and malformed-output repair, capability validation, plan-size limits, deterministic fallback, and runtime capability evidence.
