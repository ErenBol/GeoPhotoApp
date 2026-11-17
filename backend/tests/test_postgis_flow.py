"""Run with the Compose PostGIS service from backend/."""

import os
from pathlib import Path
from uuid import uuid4

import psycopg
from fastapi.testclient import TestClient

from app.main import app


def test_full_postgis_flow():
    client = TestClient(app)
    suffix = uuid4().hex[:12]
    emails = [f"test-{suffix}-{i}@example.com" for i in range(2)]
    uploads = []
    try:
        headers = []
        for i, email in enumerate(emails):
            response = client.post(
                "/api/v1/auth/register",
                json={"username": f"test-{suffix}-{i}", "email": email, "password": "test-password-123"},
            )
            assert response.status_code == 201, response.text
            headers.append({"Authorization": f"Bearer {response.json()['access_token']}"})
            assert client.post(
                "/api/v1/auth/login", json={"email": email, "password": "test-password-123"}
            ).status_code == 200

        photo_ids = []
        for i, lat in enumerate((41.0, 41.01)):
            uploaded = client.post(
                "/api/v1/photos/upload-url", headers=headers[i],
                files={"file": ("capture.jpg", b"\xff\xd8\xffsample", "image/jpeg")},
            )
            assert uploaded.status_code == 200, uploaded.text
            image_url = uploaded.json()["image_url"]
            uploads.append(image_url)
            published = client.post(
                "/api/v1/photos", headers=headers[i],
                json={"image_url": image_url, "lat": lat, "lon": 29.0},
            )
            assert published.status_code == 201, published.text
            photo_ids.append(published.json()["id"])

        feed = client.get("/api/v1/photos/nearby?lat=41.0&lon=29.0", headers=headers[0])
        assert feed.status_code == 200, feed.text
        assert feed.json()["radius_m"] == 2000
        assert feed.json()["photos"][0]["id"] == photo_ids[1]
        assert client.get("/api/v1/photos/nearby?lat=42&lon=29", headers=headers[0]).status_code == 403
        assert client.get(uploads[1], headers=headers[0]).status_code == 200
        photo = f"/api/v1/photos/{photo_ids[1]}"
        assert client.post(f"{photo}/like", headers=headers[0]).json()["liked"] is True
        assert client.post(f"{photo}/like", headers=headers[0]).json()["liked"] is False
        assert client.post(f"{photo}/comments", headers=headers[0], json={"body": "nice"}).status_code == 201
        assert len(client.get(f"{photo}/comments", headers=headers[0]).json()) == 1
        assert client.post(f"{photo}/report", headers=headers[0], json={"reason": "test"}).status_code == 201
    finally:
        with psycopg.connect(os.environ["DATABASE_URL"]) as db:
            db.execute("DELETE FROM users WHERE email = ANY(%s)", (emails,))
        for image_url in uploads:
            target = Path(__file__).resolve().parents[1] / "uploads" / image_url.removeprefix("/api/v1/photos/media/")
            target.unlink(missing_ok=True)
            target.parent.rmdir()
