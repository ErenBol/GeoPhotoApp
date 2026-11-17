"""Phase 1 API. Local uploads are temporary until object storage is configured."""

import hashlib
import hmac
import os
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID, uuid4

import jwt
import psycopg
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from psycopg.errors import UniqueViolation
from psycopg.rows import dict_row
from pydantic import BaseModel, EmailStr, Field

load_dotenv(Path(__file__).resolve().parents[1] / ".env")

API = "/api/v1"
UPLOAD_DIR = Path(__file__).resolve().parents[1] / "uploads"
MAX_UPLOAD_BYTES = 10 * 1024 * 1024
RADII_M = (500, 2000, 5000)
TOKEN_HOURS = 24
bearer = HTTPBearer(auto_error=False)
app = FastAPI(title="GeoPhoto API")


class Credentials(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8)


class Registration(Credentials):
    username: str = Field(min_length=1, max_length=50)


class PhotoCreate(BaseModel):
    image_url: str
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    caption: str | None = Field(default=None, max_length=500)


class BodyCreate(BaseModel):
    body: str = Field(min_length=1, max_length=2000)


class ReportCreate(BaseModel):
    reason: str = Field(min_length=1, max_length=500)


def get_db():
    url = os.getenv("DATABASE_URL")
    if not url:
        raise RuntimeError("DATABASE_URL is required")
    with psycopg.connect(url, row_factory=dict_row) as db:
        yield db


def jwt_secret() -> str:
    secret = os.getenv("JWT_SECRET")
    if not secret or secret.startswith("replace-with-") or len(secret.encode()) < 32:
        raise RuntimeError("Set JWT_SECRET to a private random value of at least 32 bytes")
    return secret


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1)
    return f"{salt.hex()}:{digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    salt_hex, digest_hex = stored.split(":", 1)
    digest = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt_hex), n=16384, r=8, p=1)
    return hmac.compare_digest(digest, bytes.fromhex(digest_hex))


def token_for(user_id: UUID) -> dict:
    token = jwt.encode(
        {"sub": str(user_id), "exp": datetime.now(timezone.utc) + timedelta(hours=TOKEN_HOURS)},
        jwt_secret(),
        algorithm="HS256",
    )
    return {"access_token": token, "token_type": "bearer"}


def current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> UUID:
    if credentials is None:
        raise HTTPException(401, "Bearer token required")
    try:
        return UUID(jwt.decode(credentials.credentials, jwt_secret(), algorithms=["HS256"])["sub"])
    except (jwt.InvalidTokenError, KeyError, ValueError) as exc:
        raise HTTPException(401, "Invalid token") from exc


def nearby(db, user_id: UUID, lat: float, lon: float) -> dict:
    point = "ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography"
    unlocked = db.execute(
        f"SELECT EXISTS (SELECT 1 FROM photos WHERE user_id = %s AND ST_DWithin(location, {point}, %s)) AS allowed",
        (user_id, lon, lat, RADII_M[0]),
    ).fetchone()["allowed"]
    if not unlocked:
        raise HTTPException(403, "Publish a photo within 500 m to unlock this feed")
    for radius in RADII_M:
        rows = db.execute(
            f"""SELECT p.id, p.user_id, p.image_url, p.caption, p.captured_at,
                       ST_Distance(p.location, {point}) AS distance_m,
                       (SELECT count(*) FROM likes WHERE photo_id = p.id) AS like_count,
                       (SELECT count(*) FROM comments WHERE photo_id = p.id) AS comment_count
                FROM photos p
                WHERE p.user_id <> %s AND ST_DWithin(p.location, {point}, %s)
                ORDER BY p.captured_at DESC LIMIT 50""",
            (lon, lat, user_id, lon, lat, radius),
        ).fetchall()
        if rows:
            return {"radius_m": radius, "photos": rows}
    return {"radius_m": RADII_M[-1], "photos": []}


def accessible_photo(db, user_id: UUID, photo_id: UUID) -> dict:
    photo = db.execute(
        """SELECT p.id, p.user_id, p.image_url FROM photos p
           WHERE p.id = %s AND (p.user_id = %s OR EXISTS (
               SELECT 1 FROM photos own
               WHERE own.user_id = %s AND ST_DWithin(own.location, p.location, %s)))""",
        (photo_id, user_id, user_id, RADII_M[-1]),
    ).fetchone()
    if photo is None:
        raise HTTPException(404, "Photo not found or not unlocked")
    return photo


@app.post(f"{API}/auth/register", status_code=201)
def register(data: Registration, db=Depends(get_db)):
    try:
        user = db.execute(
            "INSERT INTO users (username, email, password_hash) VALUES (%s, %s, %s) RETURNING id",
            (data.username, data.email.lower(), hash_password(data.password)),
        ).fetchone()
        db.commit()
    except UniqueViolation as exc:
        db.rollback()
        raise HTTPException(409, "Username or email already registered") from exc
    return token_for(user["id"])


@app.post(f"{API}/auth/login")
def login(data: Credentials, db=Depends(get_db)):
    user = db.execute("SELECT id, password_hash FROM users WHERE email = %s", (data.email.lower(),)).fetchone()
    if user is None or not verify_password(data.password, user["password_hash"]):
        raise HTTPException(401, "Invalid credentials")
    return token_for(user["id"])


@app.post(f"{API}/photos/upload-url")
def upload_file(file: UploadFile = File(...), user_id: UUID = Depends(current_user)):
    if file.content_type not in ("image/jpeg", "image/png"):
        raise HTTPException(415, "JPEG or PNG required")
    content = file.file.read(MAX_UPLOAD_BYTES + 1)
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "Image exceeds 10 MB")
    signature = b"\xff\xd8\xff" if file.content_type == "image/jpeg" else b"\x89PNG\r\n\x1a\n"
    if not content.startswith(signature):
        raise HTTPException(415, "Invalid image")
    suffix = ".jpg" if file.content_type == "image/jpeg" else ".png"
    key = f"{user_id}/{uuid4()}{suffix}"
    target = UPLOAD_DIR / key
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)
    return {"image_url": f"{API}/photos/media/{key}"}


@app.post(f"{API}/photos", status_code=201)
def create_photo(data: PhotoCreate, user_id: UUID = Depends(current_user), db=Depends(get_db)):
    prefix = f"{API}/photos/media/{user_id}/"
    if not data.image_url.startswith(prefix):
        raise HTTPException(400, "Upload an image first")
    filename = data.image_url.removeprefix(prefix)
    try:
        UUID(Path(filename).stem)
    except ValueError as exc:
        raise HTTPException(400, "Invalid upload key") from exc
    if filename != Path(filename).name or Path(filename).suffix not in (".jpg", ".png"):
        raise HTTPException(400, "Invalid upload key")
    key = f"{user_id}/{filename}"
    if not (UPLOAD_DIR / key).is_file():
        raise HTTPException(400, "Uploaded image not found")
    if db.execute("SELECT 1 FROM photos WHERE image_url = %s", (data.image_url,)).fetchone():
        raise HTTPException(409, "Image already published")
    try:
        row = db.execute(
            """INSERT INTO photos (user_id, image_url, caption, location)
               VALUES (%s, %s, %s, ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography)
               RETURNING id""",
            (user_id, data.image_url, data.caption, data.lon, data.lat),
        ).fetchone()
    except UniqueViolation as exc:
        db.rollback()
        raise HTTPException(409, "Image already published") from exc
    feed = nearby(db, user_id, data.lat, data.lon)
    db.commit()
    return {"id": row["id"], "feed": feed}


@app.get(f"{API}/photos/nearby")
def get_nearby(
    lat: float = Query(ge=-90, le=90), lon: float = Query(ge=-180, le=180),
    radius_m: int = Query(default=500, ge=500, le=500),
    user_id: UUID = Depends(current_user), db=Depends(get_db),
):
    return nearby(db, user_id, lat, lon)


@app.get(f"{API}/photos/media/{{owner_id}}/{{filename}}")
def get_media(owner_id: UUID, filename: str, user_id: UUID = Depends(current_user), db=Depends(get_db)):
    image_url = f"{API}/photos/media/{owner_id}/{filename}"
    row = db.execute("SELECT id FROM photos WHERE image_url = %s", (image_url,)).fetchone()
    if row is None:
        raise HTTPException(404, "Image not published")
    accessible_photo(db, user_id, row["id"])
    target = UPLOAD_DIR / str(owner_id) / filename
    if not target.is_file():
        raise HTTPException(404, "Image not found")
    return FileResponse(target)


@app.post(f"{API}/photos/{{photo_id}}/like")
def toggle_like(photo_id: UUID, user_id: UUID = Depends(current_user), db=Depends(get_db)):
    accessible_photo(db, user_id, photo_id)
    deleted = db.execute(
        "DELETE FROM likes WHERE user_id = %s AND photo_id = %s RETURNING user_id", (user_id, photo_id)
    ).fetchone()
    if deleted is None:
        db.execute("INSERT INTO likes (user_id, photo_id) VALUES (%s, %s)", (user_id, photo_id))
    db.commit()
    return {"liked": deleted is None}


@app.get(f"{API}/photos/{{photo_id}}/comments")
def list_comments(photo_id: UUID, user_id: UUID = Depends(current_user), db=Depends(get_db)):
    accessible_photo(db, user_id, photo_id)
    return db.execute(
        "SELECT id, user_id, body, created_at FROM comments WHERE photo_id = %s ORDER BY created_at, id LIMIT 100",
        (photo_id,),
    ).fetchall()


@app.post(f"{API}/photos/{{photo_id}}/comments", status_code=201)
def add_comment(photo_id: UUID, data: BodyCreate, user_id: UUID = Depends(current_user), db=Depends(get_db)):
    accessible_photo(db, user_id, photo_id)
    row = db.execute(
        "INSERT INTO comments (user_id, photo_id, body) VALUES (%s, %s, %s) RETURNING id, user_id, body, created_at",
        (user_id, photo_id, data.body),
    ).fetchone()
    db.commit()
    return row


@app.post(f"{API}/photos/{{photo_id}}/report", status_code=201)
def report(photo_id: UUID, data: ReportCreate, user_id: UUID = Depends(current_user), db=Depends(get_db)):
    accessible_photo(db, user_id, photo_id)
    row = db.execute(
        "INSERT INTO reports (reporter_id, photo_id, reason) VALUES (%s, %s, %s) RETURNING id",
        (user_id, photo_id, data.reason),
    ).fetchone()
    db.commit()
    return row
