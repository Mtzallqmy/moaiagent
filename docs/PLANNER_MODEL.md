# Model-Driven Planner

## Purpose

Phase 5 replaces pre-authored step titles in the production Plan/Agent entry path with a bounded, model-driven planning pass. Planning remains separate from hidden reasoning: the model returns only a concise structured execution plan that can be validated and shown to the user.

## Input

`PlannerInput` contains:

- `goal`
- relevant project memory
- active skills
- current verified capabilities
- optional workspace summary

Memory, skills, workspace summaries, retrieved content, and model output are treated as untrusted data. None may override application security policy or advertise capabilities that the application has not verified.

## Output schema

`StructuredTaskPlan` contains a summary and one or more `PlannedStep` values. Each step contains:

- stable plan-local `id`
- `title`
- user-visible `goal`
- `dependencies`
- `expectedCapabilities`
- observable `acceptanceCriteria`

The model is instructed to return one JSON object and no chain-of-thought or prose around it.

## Validation

`ModelDrivenPlanner` rejects a candidate when any of these checks fail:

- blank/oversized fields
- no steps or too many steps
- duplicate or malformed step IDs
- unknown/self/duplicate dependencies
- dependency cycles
- empty or oversized acceptance criteria
- expected capability not present in the verified capability set

Valid DAGs are topologically ordered before conversion to the existing task engine's ordered `TaskPlan`.

## Repair and fallback

The first invalid structured response is sent through one bounded repair attempt. The repaired candidate must pass the same validation; repair does not bypass validation.

If the provider is unavailable or the candidate remains invalid, `DeterministicGoalPlanner` creates a bounded fallback. It is goal-aware rather than a single fixed template: research-like goals and build-like goals receive materially different step sequences.

## Production integration

For Plan/Agent mode the app path is:

`ChatViewModel → ModelPlanningCoordinator → verified capabilities/context → ModelDrivenPlanner → TaskEngine.createFromPlan → AgentLoop`

If a non-terminal task already exists for the conversation/workspace, the coordinator reuses it rather than creating a duplicate plan.

Capabilities come from two sources only:

1. actual `ToolRegistry.toolsForMode(...)` entries, exposed to the planner as `tool.<name>`;
2. `RuntimeVerifier` capabilities that have passed an execution probe through AgentDroid's own `ProcessRunner`.

Runtime discovery by itself is never sufficient to advertise a language runtime.

## Persistence boundary

Room schema version 4 stores the execution-oriented ordered `TaskPlan`. The complete dependency/capability/acceptance schema exists in `StructuredTaskPlan` during the planning boundary and is validated before task creation. Phase 5 does not introduce a speculative database migration solely to duplicate this metadata; a future graph scheduler may persist the full DAG if it needs independent branch scheduling or replanning across process death.

## Acceptance coverage

Tests cover:

- materially different calculator and Android foreground-service research plans
- cycle rejection and repair
- malformed JSON repair
- rejection/repair of unadvertised capabilities
- maximum plan size
- provider failure and deterministic fallback
- Android runtime evidence separately from host detection
