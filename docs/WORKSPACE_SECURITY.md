# Workspace Security

## Trust boundary

Agent file tools receive only workspace-relative paths. `WorkspaceFileSystem` is created by application code for `filesDir/workspaces/{workspaceId}`; the model cannot choose an arbitrary Android root.

Workspace IDs used for roots are validated by the application container. Public tool paths never expose reserved internal roots used for workspace trash/metadata.

## Path validation

Before a path is used, the file system:

- rejects NUL characters;
- rejects URI schemes such as `file:`, `content:`, and `intent:`;
- rejects absolute paths;
- rejects `..` traversal;
- normalizes separators and `.` segments;
- resolves the canonical path;
- verifies the canonical result is the workspace root or a descendant.

Canonical enforcement also prevents a symlink inside the workspace from being used to escape to an external directory.

## File types and encoding

Text tools use strict UTF-8 decoding with optional UTF-8 BOM removal. Binary detection checks NUL/control-byte density and invalid UTF-8. `read_file` returns binary metadata rather than binary bytes as text; mutation/patch operations reject binary files.

Arabic and other UTF-8 text are preserved. Code files are rendered LTR in the editor while ordinary text follows application layout direction.

## Size and output limits

Defaults are configurable through `WorkspaceLimits`:

- text read: 1 MiB;
- search candidate file: 2 MiB;
- search results: 200;
- list results: 2,000;
- binary probe: 8 KiB.

Agent tool-result reinjection has a separate character limit so a valid workspace operation cannot consume the whole model context.

## Write safety

Writes use a same-directory temporary file, flush/sync, and an atomic move when supported, with a replace fallback. Existing files require explicit overwrite policy for `write_file`.

Every text mutation records SHA-256 before/after hashes. `patch_file` can require an explicit expected hash and all staged mutations revalidate current hashes/fingerprints after a permission wait and again before ChangeSet application.

## Patch conflicts

A patch is rejected with `PATCH_CONFLICT` when:

- expected SHA-256 does not match;
- exact `oldContent` is absent or ambiguous;
- a requested line range no longer matches the current file shape;
- a unified diff does not apply cleanly;
- the target changed while approval was pending;
- a destination appeared before an accepted move/create;
- a revert target no longer matches the expected applied state.

Conflicts are reported; they are never silently forced.

## Deletion and revert

Agent deletion is `DESTRUCTIVE`. Accepted deletion moves the target into `.workspace-trash/{changeSetId}` rather than irreversibly deleting it. A ChangeSet can restore the item if the destination remains conflict-free.

## Android API isolation

Provider/model output never receives direct Android filesystem or Intent APIs. All model-requested file operations pass through normalized Tool Calls, the Tool Registry, workspace validation, and the Permission Engine where required.
