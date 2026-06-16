# RepoGuard Backend Cache

RepoGuard uses Spring Cache with local Caffeine caches for read-heavy backend paths. The current design is intentionally local and lightweight; Redis can be introduced later if the backend moves to multi-instance deployment or needs distributed coordination.

## Cache Entries

| Cache | TTL | Max size | Purpose |
| --- | ---: | ---: | --- |
| `dashboardOverview` | 30 seconds | 256 | Dashboard aggregate response, keyed by `llmTrendDays`. |
| `githubOpenPullRequests` | 60 seconds | 128 | GitHub open PR picker data. |
| `reviewRules` | 10 minutes | 64 | Review rule configuration response. |

## Invalidation

`dashboardOverview` is cleared when review data can change:

- Manual review task creation or reuse.
- Review retry.
- Human review decision.
- Finding feedback update.
- Worker completion or failure.
- Review policy, system settings, GitHub integration, or review rule changes.

`githubOpenPullRequests` is cleared when GitHub integration settings change.

`reviewRules` is cleared when a review rule is created, updated, enabled, or disabled.

## Runtime Stats

Cache stats are exposed through the authenticated API:

```text
GET /api/v1/cache/stats
```

Each cache reports:

- `estimatedSize`
- `requestCount`
- `hitCount`
- `missCount`
- `hitRate`
- `evictionCount`

For a quick local check, call a cached endpoint twice and then inspect stats. For example, two calls to `GET /api/v1/dashboard/overview?llmTrendDays=7` should usually produce one miss and one hit for `dashboardOverview`.

