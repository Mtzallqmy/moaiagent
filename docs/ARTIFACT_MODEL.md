# Artifact Model

## Supported artifacts

`core:artifacts` supports Markdown, plain text, JSON, CSV, HTML, code, research report, and screenshot references. PDF generation is intentionally absent because Phase 4 requires it only when a clean, stable implementation is available.

An `Artifact` records IDs for task/conversation/workspace, type, title, workspace-relative path, MIME type, size, creation/update times, verified source references, and managed-file or external-reference storage mode.

## Repository and storage

`FileArtifactRepository` stores managed content under `Workspace/Artifacts` and a small index under `.agentdroid/artifacts-v1.json`. Writes use a temporary file and atomic replacement where supported. The repository implements create, register screenshot reference, get/list/read, update, rename, copy, delete, and export.

All paths must be workspace-relative. Absolute paths, traversal segments, reserved internal directories, canonical escapes, and managed files outside `Artifacts` are rejected. Content and reads have explicit size limits. Binary screenshots are returned by reference and are not injected into context. Deleting a screenshot reference does not delete the independently owned image.

Room's artifact table is a metadata store for application indexing; the file repository remains the owner of workspace files.

## Generation and tools

`DefaultArtifactGenerator` validates JSON, quotes CSV cells, escapes generated HTML, and creates research reports with Title, Summary, Findings, Comparison, Conclusion, and Sources.

Agent tools are `create_artifact`, `update_artifact`, `list_artifacts`, `read_artifact`, and `delete_artifact`. Delete is classified `DESTRUCTIVE`.

## Citation integrity

A `SourceReference` contains research session ID, source ID, canonical URL, optional title, and finding IDs. `CitationValidator` asks a `CitationSourceCatalog` whether the exact session/source/URL tuple exists. Duplicate, malformed, non-HTTP(S), or unknown references are rejected.

Report findings may cite only source IDs attached to the generation request. This prevents invented or unattached URLs.

## UI and tests

`Phase4ArtifactViewer` provides list, preview, open, rename, share, copy, delete, and export callbacks. Textual JSON/code previews use a monospaced style; binary content opens by file reference. Delete requires confirmation. Live navigation, sharing URI policy, and repository wiring require final application verification.

Tests cover file lifecycle, path containment, screenshot ownership, JSON validation, bounded reads, citation validation, report source enforcement, tool CRUD, and destructive delete classification.
