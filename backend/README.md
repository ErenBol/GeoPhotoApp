# GeoPhoto backend (Phase 1)

Run from `backend/`:

1. Copy `.env.example` to `.env` and replace `POSTGRES_PASSWORD` and `JWT_SECRET` with private values. Keep the password and `POSTGRES_PORT` in `DATABASE_URL` in sync. The JWT secret must be at least 32 bytes.
2. Run `docker compose up -d db`. The database initializes from `migrations/001_initial.sql` on its first empty volume. Apply later migrations explicitly to existing volumes.
3. Create a Python virtual environment, install `requirements-dev.txt`, then run `uvicorn app.main:app --reload`.
4. Open `http://127.0.0.1:8000/docs` for the API. Run `pytest -q tests` for API tests.

`POST /api/v1/photos/upload-url` currently accepts multipart `file` and saves a JPEG or PNG under `uploads/`; it returns `image_url` for `POST /api/v1/photos`. It is a local upload stub, not a presigned URL. Images are limited to 10 MB. Published media requires a bearer token and an unlocked location. The backend cannot independently prove that the Android client used a live camera; that remains a client capture requirement.

`GET /api/v1/photos/nearby` requires a previously published photo within 500 m of the requested coordinates. It searches other users' photos at 500 m, then 2 km, then 5 km until it finds any, returning `radius_m` and up to 50 photos. Likes, comments, reports, and media reads require an authenticated user with a published photo within 5 km of the target photo.
