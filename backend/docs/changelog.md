## 2026-11-18

- Added PostgreSQL/PostGIS Compose service, initial schema migration, FastAPI auth and spatial/social routes, local upload stub, and API tests.
- Validation: `pytest -q tests` passed; `docker compose --env-file .env.example config --quiet` passed. Database integration remains unverified because the local Docker daemon was unavailable.
