# Android app guidance

## Docs Index

| Document | When to read |
|---|---|
| `docs/changelog.md` | Always at session start and after compaction |
| `docs/findings.md` | When changing Android dependencies or image loading |
| `PLAN.md` | When implementing a product phase |

## Project Constraints

- Keep Coil on a version compiled for the Kotlin compiler declared in `../gradle/libs.versions.toml`; inspect dependency metadata before upgrading.
- The backend media endpoint requires a bearer token. Keep image requests authenticated and do not retain private media in shared image caches.
