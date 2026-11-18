## 2026-11-18

- Added PostgreSQL/PostGIS Compose service, initial schema migration, FastAPI auth and spatial/social routes, local upload stub, and API tests.
- Validation: `docker compose up -d db` started healthy PostGIS 3.4; `pytest -q tests` passed all 7 tests, including the database flow; `docker compose --env-file .env.example config --quiet` passed.
- Used port `55433` because `5432` and `55432` were occupied; set the local DSN host to `127.0.0.1` after `localhost` caused intermittent connection stalls on Windows.
