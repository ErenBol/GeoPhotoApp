## 2026-11-18

- Phase 4: added blocks migration and endpoint, filtered blocked photos, media, and comments, added stable 50–100 m display-coordinate jitter and 50 m distance rounding, and optional OpenAI image/text moderation. Applied migration to local PostGIS; `pytest -q tests` passed 7 tests. API smoke passed for jitter, blocking, comment filtering, and media denial; live OpenAI moderation was not run.
- Added PostgreSQL/PostGIS Compose service, initial schema migration, FastAPI auth and spatial/social routes, local upload stub, and API tests.
- Validation: `docker compose up -d db` started healthy PostGIS 3.4; `pytest -q tests` passed all 7 tests, including the database flow; `docker compose --env-file .env.example config --quiet` passed.
- Used port `55433` because `5432` and `55432` were occupied; set the local DSN host to `127.0.0.1` after `localhost` caused intermittent connection stalls on Windows.
