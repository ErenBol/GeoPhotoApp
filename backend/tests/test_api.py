from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app import main


class Result:
    def __init__(self, row=None, rows=None):
        self.row = row
        self.rows = rows or []

    def fetchone(self):
        return self.row

    def fetchall(self):
        return self.rows


class FeedDB:
    def __init__(self, unlocked=True, rows_by_radius=None):
        self.unlocked = unlocked
        self.rows_by_radius = rows_by_radius or {}
        self.radii = []

    def execute(self, sql, args):
        if "SELECT EXISTS" in sql:
            return Result({"allowed": self.unlocked})
        radius = args[-1]
        self.radii.append(radius)
        return Result(rows=self.rows_by_radius.get(radius, []))


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("JWT_SECRET", "test-only-secret-that-is-at-least-32-bytes")
    main.app.dependency_overrides[main.get_db] = lambda: None
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


def test_password_and_token(client):
    password_hash = main.hash_password("example-password")
    assert main.verify_password("example-password", password_hash)
    assert not main.verify_password("wrong-password", password_hash)
    user_id = uuid4()
    token = main.token_for(user_id)["access_token"]
    assert main.current_user(type("Bearer", (), {"credentials": token})()) == user_id
    assert client.get("/api/v1/photos/nearby?lat=0&lon=0").status_code == 401
    assert client.get("/api/v1/photos/nearby?lat=0&lon=0", headers={"Authorization": "Bearer bad"}).status_code == 401


def test_radius_fallback_and_unlock():
    user_id = uuid4()
    db = FeedDB(rows_by_radius={2000: [{"id": uuid4()}]})
    result = main.nearby(db, user_id, 41.0, 29.0)
    assert result["radius_m"] == 2000
    assert db.radii == [500, 2000]
    assert main.nearby(FeedDB(), user_id, 41.0, 29.0) == {"radius_m": 5000, "photos": []}
    with pytest.raises(main.HTTPException) as exc:
        main.nearby(FeedDB(unlocked=False), user_id, 41.0, 29.0)
    assert exc.value.status_code == 403


def test_upload_and_photo_validation(client, monkeypatch, tmp_path):
    user_id = uuid4()
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    token = main.token_for(user_id)["access_token"]
    headers = {"Authorization": f"Bearer {token}"}
    bad = client.post("/api/v1/photos/upload-url", headers=headers, files={"file": ("x.txt", b"hello", "text/plain")})
    assert bad.status_code == 415
    uploaded = client.post(
        "/api/v1/photos/upload-url", headers=headers,
        files={"file": ("x.jpg", b"\xff\xd8\xffimage", "image/jpeg")},
    )
    assert uploaded.status_code == 200
    image_url = uploaded.json()["image_url"]
    assert (tmp_path / image_url.removeprefix("/api/v1/photos/media/")).is_file()
    assert client.post("/api/v1/photos", headers=headers, json={"image_url": image_url, "lat": 91, "lon": 0}).status_code == 422
    assert client.post("/api/v1/photos", headers=headers, json={"image_url": "/api/v1/photos/media/other/x.jpg", "lat": 0, "lon": 0}).status_code == 400


def test_openapi_routes(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert {"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/photos/upload-url",
            "/api/v1/photos", "/api/v1/photos/nearby", "/api/v1/photos/{photo_id}/like",
            "/api/v1/photos/{photo_id}/comments", "/api/v1/photos/{photo_id}/report"} <= paths.keys()


def test_register_login_and_social_routes(client):
    user_id, photo_id = uuid4(), uuid4()

    class DB:
        def __init__(self):
            self.password_hash = None
            self.liked = False
            self.comments = []
            self.reports = []

        def execute(self, sql, args):
            if "INSERT INTO users" in sql:
                self.password_hash = args[2]
                return Result({"id": user_id})
            if "FROM users WHERE email" in sql:
                return Result({"id": user_id, "password_hash": self.password_hash})
            if "SELECT p.id, p.user_id, p.image_url" in sql:
                return Result({"id": photo_id, "user_id": user_id, "image_url": "image"})
            if "DELETE FROM likes" in sql:
                old = self.liked
                self.liked = False
                return Result({"user_id": user_id} if old else None)
            if "INSERT INTO likes" in sql:
                self.liked = True
                return Result()
            if "INSERT INTO comments" in sql:
                row = {"id": uuid4(), "user_id": user_id, "body": args[2], "created_at": "now"}
                self.comments.append(row)
                return Result(row)
            if "FROM comments WHERE photo_id" in sql:
                return Result(rows=self.comments)
            if "INSERT INTO reports" in sql:
                self.reports.append(args[2])
                return Result({"id": uuid4()})
            raise AssertionError(sql)

        def commit(self):
            pass

    db = DB()
    main.app.dependency_overrides[main.get_db] = lambda: db
    account = {"username": "alice", "email": "alice@example.com", "password": "correct-password"}
    registered = client.post("/api/v1/auth/register", json=account)
    assert registered.status_code == 201
    assert client.post("/api/v1/auth/login", json={"email": account["email"], "password": "wrong-password"}).status_code == 401
    logged_in = client.post("/api/v1/auth/login", json={"email": account["email"], "password": account["password"]})
    assert logged_in.status_code == 200
    headers = {"Authorization": f"Bearer {logged_in.json()['access_token']}"}
    base = f"/api/v1/photos/{photo_id}"
    assert client.post(f"{base}/like", headers=headers).json() == {"liked": True}
    assert client.post(f"{base}/like", headers=headers).json() == {"liked": False}
    assert client.post(f"{base}/comments", headers=headers, json={"body": "hello"}).status_code == 201
    assert len(client.get(f"{base}/comments", headers=headers).json()) == 1
    assert client.post(f"{base}/report", headers=headers, json={"reason": "spam"}).status_code == 201
    assert db.reports == ["spam"]


def test_publish_returns_unlocked_feed(client, monkeypatch, tmp_path):
    user_id, photo_id = uuid4(), uuid4()
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    key = f"{user_id}/{uuid4()}.jpg"
    (tmp_path / str(user_id)).mkdir()
    (tmp_path / key).write_bytes(b"\xff\xd8\xffimage")

    class DB:
        def __init__(self):
            self.published = False

        def execute(self, sql, args):
            if "SELECT 1 FROM photos WHERE image_url" in sql:
                return Result()
            if "INSERT INTO photos" in sql:
                assert args[3:] == (29.0, 41.0)
                self.published = True
                return Result({"id": photo_id})
            if "SELECT EXISTS" in sql:
                return Result({"allowed": self.published})
            if "ST_Distance" in sql:
                return Result(rows=[{"id": uuid4()}] if args[-1] == 500 else [])
            raise AssertionError(sql)

        def commit(self):
            pass

    db = DB()
    main.app.dependency_overrides[main.get_db] = lambda: db
    headers = {"Authorization": f"Bearer {main.token_for(user_id)['access_token']}"}
    response = client.post(
        "/api/v1/photos", headers=headers,
        json={"image_url": f"/api/v1/photos/media/{key}", "lat": 41.0, "lon": 29.0},
    )
    assert response.status_code == 201
    assert response.json()["feed"]["radius_m"] == 500
    assert len(response.json()["feed"]["photos"]) == 1
