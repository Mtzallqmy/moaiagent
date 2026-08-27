# Research Model

## Boundaries

`core:research` separates search, source fetching, extraction, session storage, and report generation:

- `WebSearchProvider` is the vendor-neutral search boundary.
- `ResearchSourceFetcher` retrieves one selected source with explicit limits.
- `ResearchSessionRepository` stores sessions independently of the engine.
- `ResearchExtractor` converts bounded source text into a relevant finding.
- `ResearchEngine` coordinates start/search/open/extract/compare/finalize.

Agent Core does not depend directly on DuckDuckGo or OkHttp. The module includes optional `DuckDuckGoInstantAnswerProvider` and `OkHttpResearchSourceFetcher` implementations behind those interfaces.

## Source traceability

Each `ResearchSource` records immutable session-local ID, safe HTTP(S) URL, title, domain, retrieval time, excerpt, and relevance. Each `ResearchFinding` names one or more source IDs. A finding cannot use an unknown source ID.

Inline URLs are accepted only when they match a source already present in the session. Free-form numeric citation markers are rejected during insertion. Comparison and final reports resolve source IDs from the same session, preventing invented citations.

Room schema version 4 stores sessions, sources, and findings. Persisted URLs are sanitized, and serialized reports have unsafe URL material removed.

## Bounded research

Defaults limit a session to 20 sources, 10 search results, 100 findings, 8,000 characters per excerpt/finding, 200,000 fetched text characters, and 100,000 total finding characters. Query size, response bytes, redirect count, and HTTP timeouts are bounded.

The HTTP fetcher accepts only HTTP(S), rejects credentials, validates redirects, accepts readable text/HTML only, never executes scripts, and stops on declared or observed byte-limit overflow. This is selected-source retrieval, not an unrestricted crawler.

## Workflow and tools

1. `research_start` creates a session.
2. `web_search` queries the configured provider.
3. `research_add_source` retrieves and records a selected result.
4. `research_extract` creates a finding tied to that source.
5. `research_compare` compares sourced findings.
6. `research_finalize` emits a report.

The final Markdown structure is Title, Summary, Findings, Comparison, Conclusion, and Sources. Every citation originates from a recorded session source.

## Limitations and verification

The DuckDuckGo Instant Answer API is a real optional provider but may not return a general ranked web result set for every query. Additional backends can be added without changing Agent Core. Live public-network behavior is not a CI dependency; deterministic fake providers/fetchers and local HTTP tests remain preferred.
