## 2026-11-18

- Added Phase 3 JPEG upload and publish flow, persisted unlock location, authenticated Coil nearby feed, photo detail likes/comments/report, and camera/feed navigation.
- Validation: `./gradlew.bat installDebug --no-daemon` succeeded and installed on the API 37 emulator; `git diff --check` passed. Live backend interaction was not run.
- Coil 3.6.3 failed against Kotlin 2.2 metadata; pinned Coil 3.3.0 and rebuilt successfully.
