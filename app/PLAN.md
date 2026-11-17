# Project Plan: Geo-Locked Live Photo App (MVP -> Launch)

## 1. Product Concept & Core Loop

A location-locked, live-camera social app: users can only view, like, and comment on moments within a **500m radius** after they capture and publish a live photo at that spot.

**Core User Loop:**
1. Open app -> authenticate.
2. Snap a **live camera photo** (gallery uploads disabled to enforce proof-of-presence).
3. App attaches current GPS coordinates (`lat`, `lon`) and uploads the photo.
4. Unlock the **500m radius feed** around that capture point to view, like, and comment on other users' moments left there.

### MVP Scope vs. Deferred Features

| Feature | v1 (MVP) | v2+ (Post-MVP) |
| :--- | :--- | :--- |
| **Auth** | Email/password or Google Sign-In | Phone OTP, social profiles |
| **Capture** | Live camera photo only ([CameraX](https://developer.android.com/media/camera/camerax/architecture)) | Filters, short video clips, captions |
| **Discovery** | 500m radius feed + automatic fallback expansion (2km / 5km) if empty | Interactive map of unlocked zones, "Memory Cards" for sharing |
| **Social** | Like + text comments on unlocked photos | Replies, mentions, DMs, push notifications |
| **Safety** | Report/block button, basic upload moderation, coordinate fuzzing | Automated NSFW AI moderation, shadowbanning |

---

## 2. System Architecture & Tech Stack

| Layer | Recommended Choice | Why |
| :--- | :--- | :--- |
| **Android App** | [Kotlin](https://developer.android.com/kotlin) + [Jetpack Compose](https://developer.android.com/develop/ui/compose/documentation) | Code-first declarative UI works cleanly with [Codex CLI](https://github.com/openai/codex) and [Android Studio](https://developer.android.com/studio) |
| **Camera & GPS** | [CameraX](https://developer.android.com/media/camera/camerax/architecture) + [FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient) | Official Jetpack camera abstraction + battery-efficient GPS fix |
| **Backend API** | [FastAPI](https://fastapi.tiangolo.com/) (Python) or [Supabase](https://supabase.com/docs) | Fast to build, typed Pydantic schemas, auto-generated OpenAPI docs |
| **Database** | PostgreSQL + [PostGIS (`ST_DWithin`)](https://postgis.net/docs/ST_DWithin.html) | Native `GEOGRAPHY(Point, 4326)` radius queries with GiST indexing |
| **Media Storage** | S3 / Cloudflare R2 / Supabase Storage | Never store image binaries in Postgres; store object keys/URLs only |

---

## 3. Database Schema (PostgreSQL + PostGIS)

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      TEXT UNIQUE NOT NULL,
    email         TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE photos (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    image_url     TEXT NOT NULL,
    caption       TEXT,
    location      GEOGRAPHY(Point, 4326) NOT NULL,
    captured_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_photos_location ON photos USING GIST (location);

CREATE TABLE likes (
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    photo_id      UUID NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, photo_id)
);

CREATE TABLE comments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    photo_id      UUID NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    body          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reports (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    photo_id      UUID NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    reason        TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Core Radius Query (500m):**
```sql
SELECT id, user_id, image_url, caption, captured_at,
       ST_Distance(location, ST_MakePoint(:lon, :lat)::geography) AS distance_m
FROM photos
WHERE ST_DWithin(location, ST_MakePoint(:lon, :lat)::geography, 500)
ORDER BY captured_at DESC
LIMIT 50;
```

---

## 4. REST API Endpoints (MVP)

| Method & Path | Purpose | Auth Required |
| :--- | :--- | :--- |
| `POST /api/v1/auth/register` | Create user account and return JWT | No |
| `POST /api/v1/auth/login` | Verify credentials and return JWT | No |
| `POST /api/v1/photos/upload-url` | Generate presigned URL for direct image upload | Yes |
| `POST /api/v1/photos` | Save photo metadata + `(lat, lon)` and return unlocked 500m feed | Yes |
| `GET /api/v1/photos/nearby` | Query unlocked radius (`lat`, `lon`, `radius_m=500`) | Yes |
| `POST /api/v1/photos/{id}/like` | Toggle like on a photo | Yes |
| `GET /api/v1/photos/{id}/comments` | List comments for a photo | Yes |
| `POST /api/v1/photos/{id}/comments` | Add a comment to a photo | Yes |
| `POST /api/v1/photos/{id}/report` | Flag inappropriate content for review | Yes |

---

## 5. Android App Screens & Navigation

1. **Auth Screen:** Login / Register form; stores JWT securely using EncryptedSharedPreferences or DataStore.
2. **Camera Screen (Home):** Full-screen [CameraX](https://developer.android.com/media/camera/camerax/architecture) preview + shutter button. Requests `CAMERA` and `ACCESS_FINE_LOCATION` permissions cleanly with rationale dialogs.
3. **Preview & Publish Screen:** Shows the captured photo, optional short caption, and a "Publish & Unlock 500m" button. Compresses image before upload.
4. **Nearby Feed Screen:** Displays photos unlocked within 500m (with automatic radius expansion notice if 0 nearby posts exist), showing distance badge (e.g., `120m away`), like count, and comment count.
5. **Photo Detail & Comments Screen:** Full-size photo view, like button, comment list, comment input, and a three-dot menu to **Report** or **Block** the author.

---

## 6. Phased Execution Roadmap

### Phase 0: Project & Repo Setup
- Create a monorepo or two clean repos: `backend/` (FastAPI + Docker Postgres/PostGIS) and `android/` (Android Studio Kotlin + Compose).
- Configure [Codex CLI](https://github.com/openai/codex) with `--ask-for-approval on-request` and [Mobile MCP](https://github.com/mobile-next/mobile-mcp) (used selectively for end-to-end UI checks to save tokens).

### Phase 1: Backend & Spatial API
- Spin up PostgreSQL + PostGIS via Docker Compose.
- Implement Auth (`register`/`login`), presigned object storage upload, `POST /photos`, `GET /photos/nearby` (with 500m -> 2km -> 5km fallback if empty), likes, comments, and reports.
- Verify all endpoints via FastAPI's `/docs` (Swagger UI) and `pytest`.

### Phase 2: Android Auth + Camera + GPS
- Build Auth screen and token storage.
- Implement CameraX live capture (no gallery picker) + `FusedLocationProviderClient` single high-accuracy fix.
- Test on a physical Android phone or emulator (using emulator mock GPS coordinates).

### Phase 3: Upload + Unlocked 500m Feed + Social
- Compress captured JPEG, upload to storage, and `POST /photos` to unlock the feed.
- Build Nearby Feed (`LazyColumn` + Coil image loading) and Photo Detail (Like + Comment).
- Run one targeted `mobile-mcp` pass to verify the full capture -> unlock -> like/comment flow.

### Phase 4: Safety, Polish & Internal Testing
- Add Report & Block flows (mandatory for Google Play user-generated content).
- Fuzz displayed coordinates slightly (~50–100m jitter) so exact home locations are never exposed.
- Generate a signed `.aab` in Android Studio ([App Signing guide](https://developer.android.com/studio/publish/app-signing)) and upload to [Google Play Console](https://developer.android.com/distribute/console) **Internal testing** track.

### Phase 5: Hyper-Local Launch & v2 Iteration
- Seed and launch in **one concentrated area** (e.g., your university campus or one popular neighborhood) so new users never hit an empty 500m circle.
- Ship to Production ([Publish your app](https://support.google.com/googleplay/android-developer/answer/9859751?hl=en)) and add shareable "Memory Cards" or interactive unlock maps based on user feedback.
