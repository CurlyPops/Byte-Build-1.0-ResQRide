import base64
from datetime import datetime, timezone
import json
import logging
import math
import os
from typing import Optional, List, Dict, Any
import uuid
from fastapi import Depends, FastAPI, File, HTTPException, Query, Request, Security, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
import firebase_admin
from firebase_admin import auth, credentials, firestore
import httpx
from pydantic import BaseModel, Field
from supabase import Client, create_client

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("resqride-backend")

# Auto-load .env variables if present
_env_path = os.path.join(os.path.dirname(__file__), ".env")
if os.path.exists(_env_path):
    with open(_env_path, "r", encoding="utf-8") as _f:
        for _line in _f:
            _line = _line.strip()
            if _line and not _line.startswith("#") and "=" in _line:
                _k, _v = _line.split("=", 1)
                _k, _v = _k.strip(), _v.strip()
                if _k not in os.environ:
                    os.environ[_k] = _v

app = FastAPI(
    title="ResQRide Backend API",
    description="Secure PDF upload, storage, and medical emergency verification backend",
    version="1.0.0",
)

# ---------------------------------------------------------------------------
# CORS Configuration
# ---------------------------------------------------------------------------
allowed_origins_env = os.environ.get("ALLOWED_ORIGINS")
if allowed_origins_env:
    allowed_origins = [orig.strip() for orig in allowed_origins_env.split(",") if orig.strip()]
else:
    allowed_origins = [
        "http://localhost",
        "http://localhost:3000",
        "http://localhost:5173",
        "http://localhost:8080",
        "https://resqride-oqhy.onrender.com",
    ]

app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "Accept"],
)

# ---------------------------------------------------------------------------
# Firebase Admin SDK Initialization
# ---------------------------------------------------------------------------
_firebase_initialized = False


def init_firebase():
    global _firebase_initialized
    if _firebase_initialized or bool(firebase_admin._apps):
        _firebase_initialized = True
        return

    # Check for raw or base64 JSON string from environment (Render standard)
    service_account_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON")
    service_account_path = os.environ.get("FIREBASE_SERVICE_ACCOUNT_PATH")

    try:
        if service_account_json:
            clean_str = service_account_json.strip()
            # Try raw JSON first
            if clean_str.startswith("{"):
                cred_dict = json.loads(clean_str)
            else:
                # Try Base64 decoding
                decoded_bytes = base64.b64decode(clean_str)
                cred_dict = json.loads(decoded_bytes.decode("utf-8"))

            cred = credentials.Certificate(cred_dict)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info("Firebase Admin initialized via FIREBASE_SERVICE_ACCOUNT_JSON.")
        elif service_account_path and os.path.exists(service_account_path):
            cred = credentials.Certificate(service_account_path)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info(f"Firebase Admin initialized via path: {service_account_path}")
        elif os.environ.get("GOOGLE_APPLICATION_CREDENTIALS") and os.path.exists(os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")):
            firebase_admin.initialize_app()
            _firebase_initialized = True
            logger.info("Firebase Admin initialized via GOOGLE_APPLICATION_CREDENTIALS.")
        else:
            logger.info(
                "Firebase credentials not found in environment (FIREBASE_SERVICE_ACCOUNT_JSON, FIREBASE_SERVICE_ACCOUNT_PATH, or GOOGLE_APPLICATION_CREDENTIALS). "
                "Protected endpoints requiring Firebase ID token verification will fail until credentials are provided."
            )
    except Exception as e:
        logger.error(f"Failed to initialize Firebase Admin SDK: {e}")


init_firebase()


def is_firebase_initialized() -> bool:
    return _firebase_initialized and bool(firebase_admin._apps)


# ---------------------------------------------------------------------------
# Supabase Client Initialization
# ---------------------------------------------------------------------------
SUPABASE_URL = os.environ.get("SUPABASE_URL", "").strip()
SUPABASE_SERVICE_KEY = os.environ.get("SUPABASE_SERVICE_KEY", "").strip()
BUCKET = "pdfs"
PROFILES_BUCKET = "profiles"


def get_supabase_client() -> Client:
    url = os.environ.get("SUPABASE_URL", SUPABASE_URL).strip()
    key = os.environ.get("SUPABASE_SERVICE_KEY", SUPABASE_SERVICE_KEY).strip()
    if not url or not key:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Storage service is not configured. Missing SUPABASE_URL or SUPABASE_SERVICE_KEY.",
        )
    return create_client(url, key)


# ---------------------------------------------------------------------------
# Authentication Dependency: Firebase ID Token Verification
# ---------------------------------------------------------------------------
security = HTTPBearer(auto_error=False)


async def get_current_user(
    credentials_auth: Optional[HTTPAuthorizationCredentials] = Security(security),
) -> dict:
    if not credentials_auth or not credentials_auth.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Bearer token in Authorization header.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if not is_firebase_initialized():
        # Attempt lazy initialization in case env vars were set after module load
        init_firebase()
        if not is_firebase_initialized():
            logger.warning("Authentication rejected: Firebase Admin credentials not configured.")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Authentication credentials invalid or unverified.",
                headers={"WWW-Authenticate": "Bearer"},
            )

    token = credentials_auth.credentials.strip()
    try:
        decoded_token = auth.verify_id_token(token)
        uid = decoded_token.get("uid")
        if not uid:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid token payload: missing UID.",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return decoded_token
    except auth.ExpiredIdTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Firebase ID token has expired. Please refresh token on client.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except (auth.InvalidIdTokenError, auth.CertificateFetchError) as err:
        logger.warning(f"Invalid Firebase ID token attempt: {err}")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid Firebase ID token.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except Exception as e:
        logger.error(f"Unexpected error during token verification: {e}")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication token verification failed.",
            headers={"WWW-Authenticate": "Bearer"},
        )


_frontend_dir = os.path.join(os.path.dirname(__file__), "..", "Frontend")


@app.get("/")
def root(request: Request, id: Optional[str] = None):
    accept = request.headers.get("accept", "")
    if id or "text/html" in accept:
        index_path = os.path.join(_frontend_dir, "index.html")
        if os.path.exists(index_path):
            return FileResponse(index_path)
    return {
        "message": "ResQRide backend is running",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/health")
def health():
    return {
        "status": "ok",
        "firebase_configured": is_firebase_initialized(),
        "supabase_configured": bool(os.environ.get("SUPABASE_URL") and os.environ.get("SUPABASE_SERVICE_KEY")),
    }


# ---------------------------------------------------------------------------
# PDF Storage Endpoints (Strictly Protected by Firebase UID)
# ---------------------------------------------------------------------------
@app.post("/api/files/upload")
async def upload_file(
    file: UploadFile = File(...),
    current_user: dict = Depends(get_current_user),
):
    """
    Securely uploads a single PDF document into the user's private Supabase folder:
    Path: {verified_uid}/{generated_uuid}.pdf
    Enforces MIME type check, PDF signature (%PDF-) magic bytes check, and a 10MB size limit.
    """
    uid = current_user["uid"]

    # 1. Check MIME type
    if file.content_type != "application/pdf":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Only PDF files are allowed. Received content-type: {file.content_type}",
        )

    # 2. Read file chunked to avoid memory exhaustion (Max 10 MB)
    max_size = 10 * 1024 * 1024  # 10 MB
    chunk_size = 64 * 1024  # 64 KB
    total_size = 0
    chunks = []

    while True:
        chunk = await file.read(chunk_size)
        if not chunk:
            break
        total_size += len(chunk)
        if total_size > max_size:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="PDF must be smaller than 10 MB.",
            )
        chunks.append(chunk)

    data = b"".join(chunks)

    if len(data) == 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Uploaded file is empty.",
        )

    # 3. Verify magic bytes / file signature (%PDF-)
    if not data.startswith(b"%PDF-"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File content is not a valid PDF document (missing %PDF- header).",
        )

    # 4. Generate unique storage filename
    file_id = str(uuid.uuid4())
    filename = f"{file_id}.pdf"
    storage_path = f"{uid}/{filename}"
    original_name = file.filename or "prescription.pdf"

    # 5. Upload to Supabase Private Bucket
    client = get_supabase_client()
    try:
        client.storage.from_(BUCKET).upload(
            storage_path,
            data,
            {
                "content-type": "application/pdf",
                "upsert": "false",
            },
        )
    except Exception as e:
        logger.error(f"Supabase upload failed for user {uid}: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Failed to store document in cloud storage.",
        )

    # 6. Record metadata in Firestore users/{uid}/files/{file_id}
    try:
        if is_firebase_initialized():
            db = firestore.client()
            meta_doc = {
                "fileId": file_id,
                "originalFileName": original_name,
                "storagePath": storage_path,
                "contentType": "application/pdf",
                "size": len(data),
                "createdAt": datetime.now(timezone.utc).isoformat(),
                "type": "medical_prescription",
            }
            db.collection("users").document(uid).collection("files").document(file_id).set(meta_doc)
            logger.info(f"Firestore file metadata recorded for user {uid}, file {file_id}")
    except Exception as fs_err:
        logger.warning(f"Could not write Firestore metadata: {fs_err}")

    return {
        "success": True,
        "file_id": file_id,
        "original_filename": original_name,
        "storage_path": storage_path,
        "content_type": "application/pdf",
        "size": len(data),
    }


@app.get("/api/files")
def list_files(current_user: dict = Depends(get_current_user)):
    """
    Returns the list of uploaded PDFs belonging exclusively to the authenticated user.
    """
    uid = current_user["uid"]
    client = get_supabase_client()

    try:
        storage_files = client.storage.from_(BUCKET).list(uid)
    except Exception as e:
        logger.error(f"Error listing storage files for user {uid}: {e}")
        storage_files = []

    # Optional Firestore metadata merge
    firestore_meta = {}
    try:
        if is_firebase_initialized():
            db = firestore.client()
            docs = db.collection("users").document(uid).collection("files").stream()
            for doc in docs:
                firestore_meta[doc.id] = doc.to_dict()
    except Exception as fs_err:
        logger.warning(f"Error querying Firestore files for user {uid}: {fs_err}")

    results = []
    seen_ids = set()

    for item in storage_files:
        raw_name = item.get("name", "")
        file_id = raw_name[:-4] if raw_name.endswith(".pdf") else raw_name
        seen_ids.add(file_id)
        meta = firestore_meta.get(file_id, {})

        results.append({
            "file_id": file_id,
            "filename": raw_name,
            "original_filename": meta.get("originalFileName", raw_name),
            "storage_path": f"{uid}/{raw_name}",
            "content_type": meta.get("contentType", "application/pdf"),
            "size": item.get("metadata", {}).get("size") if isinstance(item.get("metadata"), dict) else meta.get("size", 0),
            "created_at": item.get("created_at") or meta.get("createdAt"),
            "type": meta.get("type", "medical_prescription"),
        })

    # Add any records that might be tracked in Firestore
    for fid, meta in firestore_meta.items():
        if fid not in seen_ids:
            results.append({
                "file_id": fid,
                "filename": f"{fid}.pdf",
                "original_filename": meta.get("originalFileName", f"{fid}.pdf"),
                "storage_path": meta.get("storagePath", f"{uid}/{fid}.pdf"),
                "content_type": meta.get("contentType", "application/pdf"),
                "size": meta.get("size", 0),
                "created_at": meta.get("createdAt"),
                "type": meta.get("type", "medical_prescription"),
            })

    return {
        "success": True,
        "files": results,
    }


@app.get("/api/files/{file_id}")
def get_file_signed_url(
    file_id: str,
    current_user: dict = Depends(get_current_user),
):
    """
    Generates a secure, short-lived (10-minute) signed URL for the user's file.
    Enforces strict ownership: users can only obtain signed URLs for their own files.
    """
    uid = current_user["uid"]

    # Sanitize file_id to prevent directory traversal
    clean_id = os.path.basename(file_id).strip()
    if not clean_id or ".." in file_id or "/" in file_id or "\\" in file_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid file identifier.",
        )

    # Support passing file_id with or without .pdf extension
    storage_path = f"{uid}/{clean_id}" if clean_id.endswith(".pdf") else f"{uid}/{clean_id}.pdf"

    client = get_supabase_client()
    expires_in_seconds = 600  # 10 minutes

    try:
        signed_res = client.storage.from_(BUCKET).create_signed_url(
            storage_path,
            expires_in=expires_in_seconds,
        )
    except Exception as e:
        logger.error(f"Failed to generate signed URL for path {storage_path}: {e}")
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Requested file not found or inaccessible.",
        )

    signed_url = None
    if isinstance(signed_res, dict):
        signed_url = signed_res.get("signedURL") or signed_res.get("signedUrl")
    elif hasattr(signed_res, "signed_url"):
        signed_url = getattr(signed_res, "signed_url")

    if not signed_url:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Could not generate download URL for the requested file.",
        )

    return {
        "success": True,
        "file_id": clean_id[:-4] if clean_id.endswith(".pdf") else clean_id,
        "signed_url": signed_url,
        "expires_in": expires_in_seconds,
    }


@app.delete("/api/files/{file_id}")
def delete_file(
    file_id: str,
    current_user: dict = Depends(get_current_user),
):
    """
    Deletes the specified file from private storage and Firestore metadata.
    Enforces strict ownership verification.
    """
    uid = current_user["uid"]

    clean_id = os.path.basename(file_id).strip()
    if not clean_id or ".." in file_id or "/" in file_id or "\\" in file_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid file identifier.",
        )

    base_id = clean_id[:-4] if clean_id.endswith(".pdf") else clean_id
    storage_path = f"{uid}/{base_id}.pdf"

    client = get_supabase_client()
    try:
        client.storage.from_(BUCKET).remove([storage_path])
    except Exception as e:
        logger.error(f"Error deleting storage file {storage_path}: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Failed to delete file from storage.",
        )

    # Delete from Firestore if available
    try:
        if is_firebase_initialized():
            db = firestore.client()
            db.collection("users").document(uid).collection("files").document(base_id).delete()
            logger.info(f"Deleted Firestore metadata for file {base_id}")
    except Exception as fs_err:
        logger.warning(f"Could not delete Firestore file metadata: {fs_err}")

    return {
        "success": True,
        "message": "File deleted successfully",
        "file_id": base_id,
    }


# ---------------------------------------------------------------------------
# Profile & Emergency Data Models
# ---------------------------------------------------------------------------
class EmergencyContactItem(BaseModel):
    id: Optional[str] = None
    name: str
    phone: str
    relationship: str
    isPrimary: bool = False


class UserProfilePayload(BaseModel):
    uid: str
    fullName: str
    email: Optional[str] = ""
    phone: Optional[str] = ""
    age: Optional[int] = 0
    gender: Optional[str] = ""
    bloodGroup: Optional[str] = ""
    allergies: Optional[List[str]] = Field(default_factory=list)
    chronicConditions: Optional[List[str]] = Field(default_factory=list)
    medications: Optional[List[str]] = Field(default_factory=list)
    emergencyNotes: Optional[str] = ""
    emergencyContacts: Optional[List[EmergencyContactItem]] = Field(default_factory=list)
    prescriptionFileName: Optional[str] = None
    prescriptionFileId: Optional[str] = None
    prescriptionLocalUri: Optional[str] = None
    publicEmergencyUrl: Optional[str] = None
    isProfileComplete: Optional[bool] = True


REGISTERED_PROFILES: dict = {}


# ---------------------------------------------------------------------------
# Profile Management & Supabase Persistence
# ---------------------------------------------------------------------------
def _ensure_profiles_bucket(client: Client):
    try:
        client.storage.create_bucket(PROFILES_BUCKET, options={"public": True})
    except Exception:
        pass


@app.post("/api/profile")
def save_user_profile(
    payload: UserProfilePayload,
):
    """
    Saves user profile and emergency contacts into Supabase Storage & Database.
    Stores full JSON inside Supabase Storage (profiles/{uid}.json) and attempts
    upsert into 'user_profiles' or 'profiles' table if configured.
    Also synchronizes in-memory registered profiles store for immediate zero-latency access.
    """
    client = get_supabase_client()
    _ensure_profiles_bucket(client)

    uid = payload.uid.strip()
    if not uid:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing required field: uid.",
        )

    profile_dict = payload.model_dump()
    profile_dict["updated_at"] = datetime.now(timezone.utc).isoformat()
    if not profile_dict.get("publicEmergencyUrl"):
        profile_dict["publicEmergencyUrl"] = f"https://resqride-oqhy.onrender.com/med/{uid}"

    # Sync to in-memory store with normalized fields for immediate web & API access
    REGISTERED_PROFILES[uid] = {
        "id": uid,
        "name": payload.fullName,
        "age": payload.age or 0,
        "blood_group": payload.bloodGroup or "Unknown",
        "verified": True,
        "allergies": [{"name": a, "severity": "moderate"} if isinstance(a, str) else a for a in (payload.allergies or [])],
        "prescriptions": [{"name": m, "dosage": "", "frequency": ""} if isinstance(m, str) else m for m in (payload.medications or [])],
        "emergency_contacts": [
            {
                "name": c.name,
                "relation": c.relationship,
                "phone": c.phone,
                "is_primary": c.isPrimary,
            }
            for c in (payload.emergencyContacts or [])
        ],
        "medical_notes": payload.emergencyNotes or "",
        "updated_at": profile_dict["updated_at"],
    }

    # 1. Save JSON directly into Supabase Storage bucket 'profiles'
    try:
        json_bytes = json.dumps(profile_dict, indent=2).encode("utf-8")
        client.storage.from_(PROFILES_BUCKET).upload(
            f"{uid}.json",
            json_bytes,
            {"content-type": "application/json", "upsert": "true"},
        )
        logger.info(f"User profile stored in Supabase storage for uid {uid}")
    except Exception as e:
        logger.error(f"Failed storing profile in Supabase storage: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to store profile in cloud storage: {str(e)}",
        )

    # 2. Dual upsert to PostgREST table if available
    for tbl in ["user_profiles", "profiles", "users"]:
        try:
            client.table(tbl).upsert({
                "id": uid,
                "user_id": uid,
                "data": profile_dict,
                "updated_at": profile_dict["updated_at"]
            }).execute()
            break
        except Exception:
            pass

    # 3. Firestore sync if available
    try:
        if is_firebase_initialized():
            db = firestore.client()
            db.collection("users").document(uid).set(profile_dict, merge=True)
    except Exception as fs_err:
        logger.warning(f"Could not sync profile to Firestore: {fs_err}")

    return {
        "success": True,
        "message": "User profile synchronized to Supabase successfully",
        "user_id": uid,
        "public_url": profile_dict["publicEmergencyUrl"],
        "profile": profile_dict,
    }


@app.get("/api/profile/{user_id}")
def get_user_profile(user_id: str):
    """
    Retrieves stored profile JSON from Supabase storage (profiles/{uid}.json).
    Falls back to registered in-memory store and mock profiles if not found in cloud storage.
    """
    clean_uid = os.path.basename(user_id).strip()
    if not clean_uid:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid user ID.")

    client = None
    try:
        client = get_supabase_client()
    except Exception:
        pass

    if client:
        try:
            data_bytes = client.storage.from_(PROFILES_BUCKET).download(f"{clean_uid}.json")
            profile_data = json.loads(data_bytes.decode("utf-8"))
            return {"success": True, "profile": profile_data}
        except Exception as e:
            logger.warning(f"Profile not found in Supabase storage for {clean_uid}: {e}")

    # Fallback to in-memory registered profile
    if clean_uid in REGISTERED_PROFILES:
        raw = REGISTERED_PROFILES[clean_uid]
        profile_data = {
            "uid": clean_uid,
            "fullName": raw.get("name", "Unknown"),
            "email": raw.get("email", ""),
            "phone": raw.get("phone", ""),
            "age": raw.get("age", 0),
            "gender": raw.get("gender", ""),
            "bloodGroup": raw.get("blood_group", "Unknown"),
            "allergies": [a if isinstance(a, str) else a.get("name", str(a)) for a in raw.get("allergies", [])],
            "chronicConditions": [],
            "medications": [p if isinstance(p, str) else p.get("name", str(p)) for p in raw.get("prescriptions", [])],
            "emergencyNotes": raw.get("medical_notes", ""),
            "emergencyContacts": [
                {
                    "name": c.get("name", ""),
                    "phone": c.get("phone", ""),
                    "relationship": c.get("relation") or c.get("relationship", "Contact"),
                    "isPrimary": c.get("is_primary") or c.get("isPrimary", False),
                }
                for c in raw.get("emergency_contacts", [])
            ],
            "publicEmergencyUrl": f"https://resqride-oqhy.onrender.com/med/{clean_uid}",
            "isProfileComplete": True,
        }
        return {"success": True, "profile": profile_data}

    # Fallback to mock profile if demo user
    if clean_uid in MOCK_USER_PROFILES:
        raw = MOCK_USER_PROFILES[clean_uid]
        profile_data = {
            "uid": clean_uid,
            "fullName": raw.get("name", "Unknown"),
            "email": "demo@resqride.org",
            "phone": "+91 9876543210",
            "age": raw.get("age", 28),
            "gender": "Male",
            "bloodGroup": raw.get("blood_group", "Unknown"),
            "allergies": [a if isinstance(a, str) else a.get("name", str(a)) for a in raw.get("allergies", [])],
            "chronicConditions": ["Type 2 Diabetes"],
            "medications": [p if isinstance(p, str) else p.get("name", str(p)) for p in raw.get("prescriptions", [])],
            "emergencyNotes": raw.get("medical_notes", ""),
            "emergencyContacts": [
                {
                    "name": c.get("name", ""),
                    "phone": c.get("phone", ""),
                    "relationship": c.get("relation") or c.get("relationship", "Contact"),
                    "isPrimary": True if i == 0 else False,
                }
                for i, c in enumerate(raw.get("emergency_contacts", []))
            ],
            "publicEmergencyUrl": f"https://resqride-oqhy.onrender.com/med/{clean_uid}",
            "isProfileComplete": True,
        }
        return {"success": True, "profile": profile_data}

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Profile not found.")


# ---------------------------------------------------------------------------
# Dynamic First Responder Emergency Medical Triage Endpoints
# ---------------------------------------------------------------------------
def _get_user_medical_data_with_pdfs(user_id: str) -> dict:
    """
    Fetches user profile and queries Supabase Storage for all uploaded prescription PDFs,
    generating signed download URLs.
    Checks Supabase Storage 'profiles/{clean_uid}.json', then in-memory store and mock profiles.
    """
    client = None
    try:
        client = get_supabase_client()
    except Exception:
        pass

    clean_uid = os.path.basename(user_id).strip()

    profile_data = {}
    if client:
        try:
            data_bytes = client.storage.from_(PROFILES_BUCKET).download(f"{clean_uid}.json")
            profile_data = json.loads(data_bytes.decode("utf-8"))
        except Exception as e:
            logger.warning(f"Could not load profile from Supabase storage for {clean_uid}: {e}")

    # Fallback to in-memory registered or mock profiles if not in Supabase storage
    if not profile_data:
        if clean_uid in REGISTERED_PROFILES:
            raw = REGISTERED_PROFILES[clean_uid]
            profile_data = {
                "uid": clean_uid,
                "fullName": raw.get("name") or raw.get("fullName", "Rider"),
                "age": raw.get("age"),
                "bloodGroup": raw.get("blood_group") or raw.get("bloodGroup", "Unknown"),
                "allergies": [a if isinstance(a, str) else a.get("name", str(a)) for a in raw.get("allergies", [])],
                "emergencyContacts": [
                    {
                        "name": c.get("name", "Contact"),
                        "phone": c.get("phone", ""),
                        "relationship": c.get("relation") or c.get("relationship", "Contact"),
                        "isPrimary": c.get("is_primary") or c.get("isPrimary", False),
                    }
                    for c in raw.get("emergency_contacts", [])
                ],
                "emergencyNotes": raw.get("medical_notes") or raw.get("emergencyNotes", ""),
                "publicEmergencyUrl": f"https://resqride-oqhy.onrender.com/med/{clean_uid}",
            }
        elif clean_uid in MOCK_USER_PROFILES:
            raw = MOCK_USER_PROFILES[clean_uid]
            profile_data = {
                "uid": clean_uid,
                "fullName": raw.get("name", "Rider"),
                "age": raw.get("age"),
                "bloodGroup": raw.get("blood_group", "Unknown"),
                "allergies": [a if isinstance(a, str) else a.get("name", str(a)) for a in raw.get("allergies", [])],
                "emergencyContacts": [
                    {
                        "name": c.get("name", "Contact"),
                        "phone": c.get("phone", ""),
                        "relationship": c.get("relation") or c.get("relationship", "Contact"),
                        "isPrimary": c.get("is_primary") or c.get("isPrimary", False),
                    }
                    for c in raw.get("emergency_contacts", [])
                ],
                "emergencyNotes": raw.get("medical_notes", ""),
                "publicEmergencyUrl": f"https://resqride-oqhy.onrender.com/med/{clean_uid}",
            }

    # Normalize fields in profile_data if any alternate naming was used
    if profile_data:
        if "fullName" not in profile_data and "name" in profile_data:
            profile_data["fullName"] = profile_data["name"]
        if "bloodGroup" not in profile_data and "blood_group" in profile_data:
            profile_data["bloodGroup"] = profile_data["blood_group"]
        if "emergencyNotes" not in profile_data and "medical_notes" in profile_data:
            profile_data["emergencyNotes"] = profile_data["medical_notes"]
        if "emergencyContacts" not in profile_data and "emergency_contacts" in profile_data:
            profile_data["emergencyContacts"] = profile_data["emergency_contacts"]

    # Query uploaded PDFs in Supabase storage 'pdfs/{clean_uid}/'
    pdf_docs = []
    if client:
        try:
            storage_files = client.storage.from_(BUCKET).list(clean_uid)
            for item in storage_files:
                file_name = item.get("name", "")
                if file_name.lower().endswith(".pdf"):
                    path = f"{clean_uid}/{file_name}"
                    try:
                        signed_res = client.storage.from_(BUCKET).create_signed_url(path, 3600)
                        surl = None
                        if isinstance(signed_res, dict):
                            surl = signed_res.get("signedURL") or signed_res.get("signedUrl")
                        elif hasattr(signed_res, "signed_url"):
                            surl = getattr(signed_res, "signed_url")

                        pdf_docs.append({
                            "filename": file_name,
                            "storage_path": path,
                            "signed_url": surl,
                        })
                    except Exception as sign_err:
                        logger.warning(f"Error signing URL for {path}: {sign_err}")
        except Exception as list_err:
            logger.warning(f"Error listing PDFs for {clean_uid}: {list_err}")

    # Fallback to mock prescriptions if demo user and no storage PDFs found
    if not pdf_docs and (clean_uid in MOCK_USER_PROFILES or "demo" in clean_uid):
        for rx in MOCK_USER_PRESCRIPTIONS.get(clean_uid, []):
            pdf_docs.append({
                "filename": rx.get("filename", "Prescription.pdf"),
                "storage_path": f"{clean_uid}/{rx.get('file_id', 'doc')}.pdf",
                "signed_url": f"https://resqride-oqhy.onrender.com/api/emergency/{clean_uid}/prescriptions",
            })

    return {
        "user_id": clean_uid,
        "profile": profile_data,
        "prescription_documents": pdf_docs,
    }


@app.get("/api/med/{user_id}")
def get_emergency_medical_json(user_id: str):
    """
    JSON API for dynamic emergency triage data including rider vitals and PDF signed URLs.
    """
    data = _get_user_medical_data_with_pdfs(user_id)
    if not data["profile"]:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Emergency medical record not found for this identifier.",
        )
    return {"success": True, "data": data}


@app.get("/med/{user_id}", response_class=HTMLResponse)
def get_emergency_medical_triage_card(user_id: str):
    """
    Dynamic HTML Emergency Triage Card scanned by paramedics and first responders at accident scenes.
    Loads instant medical profile and prescription PDFs stored in Supabase.
    """
    data = _get_user_medical_data_with_pdfs(user_id)
    profile = data.get("profile", {})
    pdf_docs = data.get("prescription_documents", [])

    clean_uid = os.path.basename(user_id).strip()

    if not profile:
        html_not_found = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ResQRide — Medical ID Not Found</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0b0f19; color: #f3f4f6; margin: 0; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 100vh; }}
        .card {{ background: #1f2937; border-radius: 16px; border: 2px solid #ef4444; padding: 28px; max-width: 480px; width: 100%; box-shadow: 0 10px 30px rgba(239, 68, 68, 0.2); text-align: center; }}
        .badge {{ background: #ef4444; color: #fff; padding: 6px 14px; border-radius: 999px; font-weight: bold; font-size: 13px; text-transform: uppercase; letter-spacing: 1px; display: inline-block; margin-bottom: 16px; }}
        h1 {{ font-size: 22px; margin: 0 0 12px; color: #fff; }}
        p {{ color: #9ca3af; font-size: 14px; line-height: 1.5; margin: 0 0 20px; }}
        .btn {{ display: inline-block; background: #ef4444; color: #fff; text-decoration: none; padding: 12px 24px; border-radius: 10px; font-weight: bold; font-size: 15px; }}
        .id-badge {{ background: #374151; padding: 8px 12px; border-radius: 8px; font-family: monospace; font-size: 13px; color: #d1d5db; margin-bottom: 20px; }}
    </style>
</head>
<body>
    <div class="card">
        <div class="badge">Emergency System</div>
        <h1>Rider Profile Pending Sync</h1>
        <p>Medical profile for rider identifier <strong>{clean_uid}</strong> has not yet synced to the cloud or is offline.</p>
        <div class="id-badge">ID: {clean_uid}</div>
        <p>If this is a crash scene, immediately contact local emergency services:</p>
        <a href="tel:112" class="btn">Dial 112 (Emergency Response)</a>
    </div>
</body>
</html>"""
        return HTMLResponse(content=html_not_found, status_code=200)

    rider_name = profile.get("fullName") or "Anonymous Rider"
    blood_group = profile.get("bloodGroup") or "Unknown"
    age = profile.get("age") or "N/A"
    gender = profile.get("gender") or "N/A"
    phone = profile.get("phone") or "N/A"
    allergies = profile.get("allergies") or []
    conditions = profile.get("chronicConditions") or []
    med_notes = profile.get("emergencyNotes") or "No special medical instructions provided."
    contacts = profile.get("emergencyContacts") or profile.get("emergency_contacts") or []

    if allergies:
        allergies_html = "".join([f'<span class="tag tag-red">{a if isinstance(a, str) else a.get("name", str(a))}</span>' for a in allergies])
    else:
        allergies_html = '<span class="tag tag-gray">No Known Drug Allergies (NKDA)</span>'

    if conditions:
        conditions_html = "".join([f'<span class="tag tag-amber">{c if isinstance(c, str) else c.get("name", str(c))}</span>' for c in conditions])
    else:
        conditions_html = '<span class="tag tag-gray">None Reported</span>'

    contacts_html = ""
    for idx, c in enumerate(contacts):
        c_name = c.get("name", "Emergency Contact")
        c_rel = c.get("relationship") or c.get("relation", "Contact")
        c_phone = c.get("phone", "")
        clean_p = c_phone.replace(" ", "").replace("-", "")
        is_prim = c.get("isPrimary") or c.get("is_primary", False) or idx == 0
        prim_badge = '<span class="prim-badge">PRIMARY</span>' if is_prim else ""
        contacts_html += f"""
        <div class="contact-item">
            <div class="contact-info">
                <div class="contact-name">{c_name} {prim_badge}</div>
                <div class="contact-rel">{c_rel} • {c_phone}</div>
            </div>
            <a href="tel:{clean_p}" class="call-btn">📞 Call</a>
        </div>
        """

    if not contacts_html:
        contacts_html = '<p class="muted">No emergency contacts registered.</p>'

    pdfs_html = ""
    for pdf in pdf_docs:
        p_name = pdf.get("filename", "Prescription.pdf")
        p_url = pdf.get("signed_url", "#")
        pdfs_html += f"""
        <a href="{p_url}" target="_blank" class="pdf-btn">
            <span class="pdf-icon">📄</span>
            <span class="pdf-name">{p_name}</span>
            <span class="pdf-action">View PDF ↗</span>
        </a>
        """

    if not pdfs_html:
        pname = profile.get("prescriptionFileName")
        if pname:
            pdfs_html = f'<p class="muted">Prescription: {pname} (Document pending cloud sync)</p>'
        else:
            pdfs_html = '<p class="muted">No attached prescription or medical records.</p>'

    html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🚨 FIRST RESPONDER MEDICAL TRIAGE — {rider_name}</title>
    <style>
        :root {{
            --bg-dark: #090d16;
            --card-bg: #131b2e;
            --red: #ef4444;
            --red-glow: rgba(239, 68, 68, 0.35);
            --amber: #f59e0b;
            --emerald: #10b981;
            --text-main: #f9fafb;
            --text-muted: #9ca3af;
            --border: #1f293d;
        }}
        * {{ box-sizing: border-box; }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
            background: var(--bg-dark);
            color: var(--text-main);
            margin: 0;
            padding: 12px;
            display: flex;
            justify-content: center;
        }}
        .container {{
            max-width: 540px;
            width: 100%;
            margin-bottom: 24px;
        }}
        .banner {{
            background: linear-gradient(135deg, #b91c1c, #dc2626);
            color: #fff;
            padding: 14px 16px;
            border-radius: 14px;
            text-align: center;
            font-weight: 800;
            font-size: 13px;
            letter-spacing: 1px;
            text-transform: uppercase;
            box-shadow: 0 4px 20px var(--red-glow);
            margin-bottom: 12px;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
        }}
        .card {{
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 18px;
            padding: 20px;
            margin-bottom: 14px;
            box-shadow: 0 4px 15px rgba(0,0,0,0.3);
        }}
        .header-grid {{
            display: grid;
            grid-template-columns: 1fr auto;
            align-items: center;
            gap: 16px;
        }}
        .rider-name {{
            font-size: 24px;
            font-weight: 800;
            color: #fff;
            margin: 0 0 6px 0;
        }}
        .rider-sub {{
            font-size: 13px;
            color: var(--text-muted);
            margin: 0;
        }}
        .blood-badge {{
            background: linear-gradient(135deg, #ef4444, #991b1b);
            color: #fff;
            padding: 12px 18px;
            border-radius: 14px;
            text-align: center;
            box-shadow: 0 4px 16px var(--red-glow);
            min-width: 80px;
        }}
        .blood-label {{
            font-size: 10px;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            opacity: 0.9;
        }}
        .blood-val {{
            font-size: 28px;
            font-weight: 900;
            line-height: 1.1;
        }}
        .sec-title {{
            font-size: 12px;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.8px;
            color: var(--text-muted);
            margin-bottom: 10px;
            display: flex;
            align-items: center;
            gap: 6px;
        }}
        .tag-group {{
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
        }}
        .tag {{
            display: inline-block;
            padding: 6px 12px;
            border-radius: 8px;
            font-size: 13px;
            font-weight: 600;
        }}
        .tag-red {{
            background: rgba(239, 68, 68, 0.2);
            color: #fca5a5;
            border: 1px solid rgba(239, 68, 68, 0.4);
        }}
        .tag-amber {{
            background: rgba(245, 158, 11, 0.2);
            color: #fcd34d;
            border: 1px solid rgba(245, 158, 11, 0.4);
        }}
        .tag-gray {{
            background: rgba(156, 163, 175, 0.15);
            color: #9ca3af;
            border: 1px solid rgba(156, 163, 175, 0.3);
        }}
        .notes-box {{
            background: #0d1322;
            border-left: 4px solid var(--amber);
            padding: 12px 14px;
            border-radius: 6px 10px 10px 6px;
            font-size: 13px;
            line-height: 1.5;
            color: #e5e7eb;
        }}
        .contact-item {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 12px;
            background: #0d1322;
            border-radius: 12px;
            margin-bottom: 8px;
            border: 1px solid var(--border);
        }}
        .contact-name {{
            font-weight: 700;
            font-size: 15px;
            color: #fff;
            display: flex;
            align-items: center;
            gap: 6px;
        }}
        .prim-badge {{
            background: var(--emerald);
            color: #000;
            font-size: 9px;
            font-weight: 900;
            padding: 2px 6px;
            border-radius: 4px;
            letter-spacing: 0.5px;
        }}
        .contact-rel {{
            font-size: 12px;
            color: var(--text-muted);
            margin-top: 2px;
        }}
        .call-btn {{
            background: #10b981;
            color: #fff;
            text-decoration: none;
            padding: 10px 18px;
            border-radius: 10px;
            font-weight: 700;
            font-size: 13px;
            display: inline-flex;
            align-items: center;
            gap: 4px;
            box-shadow: 0 4px 12px rgba(16, 185, 129, 0.3);
        }}
        .pdf-btn {{
            display: flex;
            align-items: center;
            gap: 12px;
            background: #0d1322;
            border: 1px solid #2563eb;
            color: #93c5fd;
            text-decoration: none;
            padding: 12px 14px;
            border-radius: 10px;
            margin-top: 8px;
            font-weight: 600;
            font-size: 13px;
        }}
        .pdf-icon {{ font-size: 20px; }}
        .pdf-name {{ flex: 1; word-break: break-all; }}
        .pdf-action {{ font-size: 12px; color: #60a5fa; font-weight: bold; }}
        .muted {{ color: var(--text-muted); font-size: 13px; margin: 0; }}
        .footer {{
            text-align: center;
            font-size: 11px;
            color: #6b7280;
            margin-top: 18px;
        }}
    </style>
</head>
<body>
    <div class="container">
        <div class="banner">
            🚨 FIRST RESPONDER EMERGENCY MEDICAL ID
        </div>

        <div class="card">
            <div class="header-grid">
                <div>
                    <h1 class="rider-name">{rider_name}</h1>
                    <p class="rider-sub">Age: <strong>{age}</strong> • Gender: <strong>{gender}</strong></p>
                    <p class="rider-sub" style="margin-top: 4px;">Phone: <strong>{phone}</strong></p>
                </div>
                <div class="blood-badge">
                    <div class="blood-label">BLOOD</div>
                    <div class="blood-val">{blood_group}</div>
                </div>
            </div>
        </div>

        <div class="card">
            <div class="sec-title">⚠️ Known Drug / Medical Allergies</div>
            <div class="tag-group">
                {allergies_html}
            </div>
        </div>

        <div class="card">
            <div class="sec-title">🏥 Chronic Medical Conditions</div>
            <div class="tag-group">
                {conditions_html}
            </div>
        </div>

        <div class="card">
            <div class="sec-title">📋 Emergency Medical Instructions</div>
            <div class="notes-box">
                {med_notes}
            </div>
        </div>

        <div class="card">
            <div class="sec-title">📞 Emergency Contacts (Tap to Call)</div>
            {contacts_html}
        </div>

        <div class="card">
            <div class="sec-title">📑 Attached Prescription & Medical PDFs</div>
            {pdfs_html}
        </div>

        <div style="text-align: center; margin: 18px 0 10px 0;">
            <a href="/index.html?rider={clean_uid}" style="display: inline-block; background: #2563eb; color: #fff; text-decoration: none; padding: 12px 20px; border-radius: 12px; font-weight: 700; font-size: 14px; box-shadow: 0 4px 14px rgba(37, 99, 235, 0.4);">
                🗺️ Open Hospital Navigator & Live Route
            </a>
        </div>

        <div class="footer">
            ResQRide Universal Smart Emergency Response<br>
            Hosted on <a href="https://resqride-oqhy.onrender.com" style="color: #60a5fa; text-decoration: none;">https://resqride-oqhy.onrender.com</a>
        </div>
    </div>
</body>
</html>"""
    return HTMLResponse(content=html_content, status_code=200)


# ---------------------------------------------------------------------------
# Mock & Registered Data (used for demo, offline test, or when Supabase is not yet populated)
# ---------------------------------------------------------------------------
MOCK_USER_PROFILES = {
    "demo-user-001": {
        "id": "demo-user-001",
        "name": "Arjun Mehta",
        "age": 28,
        "blood_group": "B+",
        "verified": True,
        "allergies": [
            {"name": "Penicillin", "severity": "severe"},
            {"name": "Sulfa Drugs", "severity": "severe"},
            {"name": "Dust Mites", "severity": "moderate"},
            {"name": "Latex", "severity": "mild"},
        ],
        "prescriptions": [
            {"name": "Metformin", "dosage": "500mg", "frequency": "Twice daily"},
            {"name": "Atorvastatin", "dosage": "10mg", "frequency": "Once at bedtime"},
            {"name": "Cetirizine", "dosage": "10mg", "frequency": "Once daily (as needed)"},
        ],
        "emergency_contacts": [
            {"name": "Priya Mehta", "relation": "Wife", "phone": "+919876543210"},
            {"name": "Rajesh Mehta", "relation": "Father", "phone": "+919812345678"},
            {"name": "Dr. Kavita Sharma", "relation": "Family Doctor", "phone": "+919988776655"},
        ],
        "medical_notes": "Type 2 Diabetes (controlled). Mild seasonal allergies. No surgical history.",
        "updated_at": datetime.now(timezone.utc).isoformat(),
    },
    "demo-user-002": {
        "id": "demo-user-002",
        "name": "Pooja Verma",
        "age": 24,
        "blood_group": "O-",
        "verified": True,
        "allergies": [
            {"name": "Peanuts", "severity": "severe"},
            {"name": "Aspirin / NSAIDs", "severity": "severe"},
            {"name": "Cat Dander", "severity": "moderate"},
        ],
        "prescriptions": [
            {"name": "Budecort Inhaler", "dosage": "200mcg", "frequency": "Twice daily"},
            {"name": "Levocetirizine", "dosage": "5mg", "frequency": "Once daily at night"},
            {"name": "EpiPen Auto-Injector", "dosage": "0.3mg", "frequency": "Carry always (emergency)"},
        ],
        "emergency_contacts": [
            {"name": "Ananya Verma", "relation": "Sister", "phone": "+919823456789"},
            {"name": "Sunil Verma", "relation": "Father", "phone": "+919834567890"},
            {"name": "Dr. A. Sen", "relation": "Pulmonologist", "phone": "+919845678901"},
        ],
        "medical_notes": "Chronic Asthma & Anaphylactic Peanut Allergy. Always carry EpiPen in helmet kit.",
        "updated_at": datetime.now(timezone.utc).isoformat(),
    },
    "demo-user-003": {
        "id": "demo-user-003",
        "name": "Rohan Deshmukh",
        "age": 32,
        "blood_group": "A+",
        "verified": True,
        "allergies": [
            {"name": "Pollen", "severity": "mild"},
            {"name": "Shellfish", "severity": "moderate"},
        ],
        "prescriptions": [
            {"name": "Telmisartan", "dosage": "40mg", "frequency": "Once daily morning"},
            {"name": "Vitamin D3", "dosage": "60000 IU", "frequency": "Once weekly"},
        ],
        "emergency_contacts": [
            {"name": "Snehal Deshmukh", "relation": "Spouse", "phone": "+919856789012"},
            {"name": "Vikram Deshmukh", "relation": "Brother", "phone": "+919867890123"},
            {"name": "Dr. M. Iyer", "relation": "Cardiologist", "phone": "+919878901234"},
        ],
        "medical_notes": "Primary Hypertension. Titanium implant in left collarbone (2023).",
        "updated_at": datetime.now(timezone.utc).isoformat(),
    },
}

MOCK_USER_PROFILE = MOCK_USER_PROFILES["demo-user-001"]

MOCK_USER_PRESCRIPTIONS = {
    "demo-user-001": [
        {
            "file_id": "mock-rx-001",
            "filename": "Dr_Sharma_Endocrinology_Prescription.pdf",
            "signed_url": None,
            "content_type": "application/pdf",
            "size": 245760,
            "created_at": "2026-08-20T10:30:00Z",
            "type": "medical_prescription",
            "doctor": "Dr. Kavita Sharma, MD",
            "clinic": "Max Healthcare Saket, New Delhi",
            "medications": ["Metformin 500mg BD", "Atorvastatin 10mg HS", "Cetirizine 10mg PRN"],
        },
        {
            "file_id": "mock-rx-002",
            "filename": "Blood_Glucose_HbA1c_Lab_Report.pdf",
            "signed_url": None,
            "content_type": "application/pdf",
            "size": 189440,
            "created_at": "2026-09-05T14:15:00Z",
            "type": "lab_report",
            "doctor": "Dr. S. Nair, Pathologist",
            "clinic": "Dr. Lal PathLabs, Delhi",
            "medications": ["HbA1c: 6.8% (Controlled)", "Fasting Glucose: 112 mg/dL"],
        },
    ],
    "demo-user-002": [
        {
            "file_id": "mock-rx-003",
            "filename": "Asthma_Action_Plan_Pulmonology.pdf",
            "signed_url": None,
            "content_type": "application/pdf",
            "size": 312000,
            "created_at": "2026-07-14T09:00:00Z",
            "type": "medical_prescription",
            "doctor": "Dr. A. Sen, Pulmonologist",
            "clinic": "Fortis Hospital, Okhla",
            "medications": ["Budecort Inhaler 200mcg 1 puff BD", "EpiPen 0.3mg Auto-injector SOS"],
        },
        {
            "file_id": "mock-rx-004",
            "filename": "Allergy_Panel_Immunology_Diagnosis.pdf",
            "signed_url": None,
            "content_type": "application/pdf",
            "size": 215000,
            "created_at": "2026-08-11T16:20:00Z",
            "type": "allergy_report",
            "doctor": "Dr. R. Kapoor, Immunologist",
            "clinic": "Apollo Hospital, Sarita Vihar",
            "medications": ["Strict peanut/NSAID avoidance", "Emergency protocol documented"],
        },
    ],
    "demo-user-003": [
        {
            "file_id": "mock-rx-005",
            "filename": "Cardiology_Hypertension_Care_Plan.pdf",
            "signed_url": None,
            "content_type": "application/pdf",
            "size": 268000,
            "created_at": "2026-08-28T11:45:00Z",
            "type": "medical_prescription",
            "doctor": "Dr. M. Iyer, Cardiologist",
            "clinic": "AIIMS Cardiology OPD",
            "medications": ["Telmisartan 40mg OD morning", "Vitamin D3 60k weekly"],
        },
    ],
}


MOCK_HOSPITALS = [
    {
        "name": "AIIMS Trauma Centre",
        "address": "Sri Aurobindo Marg, Ansari Nagar, New Delhi",
        "distance_km": 1.2,
        "open_now": True,
        "phone": "+911126588500",
        "lat": 28.5672,
        "lng": 77.2100,
        "rating": 4.3,
    },
    {
        "name": "Safdarjung Hospital",
        "address": "Ansari Nagar West, New Delhi",
        "distance_km": 2.5,
        "open_now": True,
        "phone": "+911126707437",
        "lat": 28.5685,
        "lng": 77.2065,
        "rating": 4.0,
    },
    {
        "name": "Max Super Speciality Hospital",
        "address": "Saket, New Delhi",
        "distance_km": 3.8,
        "open_now": True,
        "phone": "+911126515050",
        "lat": 28.5274,
        "lng": 77.2149,
        "rating": 4.5,
    },
    {
        "name": "Apollo Hospital",
        "address": "Mathura Road, Sarita Vihar, New Delhi",
        "distance_km": 5.1,
        "open_now": True,
        "phone": "+911126925858",
        "lat": 28.5306,
        "lng": 77.2875,
        "rating": 4.4,
    },
    {
        "name": "Fortis Escorts Heart Institute",
        "address": "Okhla Road, New Delhi",
        "distance_km": 6.3,
        "open_now": False,
        "phone": "+911147135000",
        "lat": 28.5555,
        "lng": 77.2765,
        "rating": 4.6,
    },
]


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate the great-circle distance between two points on Earth in km."""
    R = 6371.0
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = (
        math.sin(d_lat / 2) ** 2
        + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lon / 2) ** 2
    )
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


# ---------------------------------------------------------------------------
# Public Emergency Endpoints (NO AUTH — QR scan target)
# ---------------------------------------------------------------------------
@app.get("/api/emergency/profiles")
def list_demo_profiles():
    """
    PUBLIC endpoint — returns available demo profiles so anyone testing
    can easily switch riders and verify personalized data.
    """
    combined = {**MOCK_USER_PROFILES, **REGISTERED_PROFILES}
    return {
        "success": True,
        "profiles": [
            {
                "id": p["id"],
                "name": p["name"],
                "blood_group": p["blood_group"],
                "age": p.get("age"),
            }
            for p in combined.values()
        ],
    }


@app.post("/api/emergency/profile")
def save_emergency_profile(payload: dict):
    """
    PUBLIC sign-up/profile endpoint — saves personalized sign-up info
    (name, blood group, allergies, prescriptions, contacts) to Supabase Storage,
    Supabase table, Firestore, and in-memory store so it is immediately accessible
    via the QR code URL.
    """
    user_id = payload.get("id") or f"rider-{uuid.uuid4().hex[:8]}"
    profile = {
        "id": user_id,
        "name": payload.get("name", "Unknown Rider"),
        "age": payload.get("age", 25),
        "blood_group": payload.get("blood_group", "O+"),
        "verified": True,
        "allergies": payload.get("allergies", []),
        "prescriptions": payload.get("prescriptions", []),
        "emergency_contacts": payload.get("emergency_contacts", []),
        "medical_notes": payload.get("medical_notes", ""),
        "updated_at": datetime.now(timezone.utc).isoformat(),
    }

    REGISTERED_PROFILES[user_id] = profile

    # 1. Persist to Supabase Storage bucket 'profiles/{user_id}.json'
    try:
        client = get_supabase_client()
        _ensure_profiles_bucket(client)
        storage_dict = {
            "uid": user_id,
            "fullName": profile["name"],
            "age": profile["age"],
            "bloodGroup": profile["blood_group"],
            "allergies": [a if isinstance(a, str) else a.get("name", str(a)) for a in profile["allergies"]],
            "emergencyContacts": [
                {
                    "name": c.get("name", ""),
                    "phone": c.get("phone", ""),
                    "relationship": c.get("relation") or c.get("relationship", "Contact"),
                    "isPrimary": c.get("is_primary") or c.get("isPrimary", False),
                }
                for c in profile["emergency_contacts"]
            ],
            "emergencyNotes": profile["medical_notes"],
            "publicEmergencyUrl": f"https://resqride-oqhy.onrender.com/med/{user_id}",
            "updated_at": profile["updated_at"],
        }
        json_bytes = json.dumps(storage_dict, indent=2).encode("utf-8")
        client.storage.from_(PROFILES_BUCKET).upload(
            f"{user_id}.json",
            json_bytes,
            {"content-type": "application/json", "upsert": "true"},
        )
        logger.info(f"Emergency profile uploaded to Supabase Storage profiles/{user_id}.json")
    except Exception as e:
        logger.warning(f"Could not persist profile to Supabase Storage: {e}")

    # 2. Persist to Supabase table if configured
    try:
        url = os.environ.get("SUPABASE_URL", SUPABASE_URL).strip()
        key = os.environ.get("SUPABASE_SERVICE_KEY", SUPABASE_SERVICE_KEY).strip()
        if url and key:
            client = create_client(url, key)
            client.table("users").upsert(profile).execute()
            logger.info(f"User profile saved to Supabase table: {user_id}")
    except Exception as e:
        logger.warning(f"Could not persist profile to Supabase table: {e}")

    # 3. Persist to Firestore if configured
    try:
        if is_firebase_initialized():
            db = firestore.client()
            db.collection("users").document(user_id).set(profile)
            logger.info(f"User profile saved to Firestore: {user_id}")
    except Exception as e:
        logger.warning(f"Could not persist profile to Firestore: {e}")

    return {
        "success": True,
        "message": "Profile saved successfully",
        "user_id": user_id,
        "profile": profile,
    }


@app.get("/api/emergency/{user_id}")
def get_emergency_profile(user_id: str):
    """
    PUBLIC endpoint — returns the medical profile for a user.
    This is the QR-code scan target. No authentication required.
    Checks in-memory registered store, Supabase Storage profiles/{user_id}.json,
    Supabase 'users' table, and demo profiles.
    """
    # 1. Check in-memory registered profiles first (e.g. from sign up during session)
    if user_id in REGISTERED_PROFILES:
        return {
            "success": True,
            "source": "memory",
            "profile": REGISTERED_PROFILES[user_id],
        }

    # 2. Try fetching from Supabase Storage profiles/{user_id}.json
    try:
        client = get_supabase_client()
        clean_uid = os.path.basename(user_id).strip()
        data_bytes = client.storage.from_(PROFILES_BUCKET).download(f"{clean_uid}.json")
        stored = json.loads(data_bytes.decode("utf-8"))
        profile = {
            "id": stored.get("uid", user_id),
            "name": stored.get("fullName") or stored.get("name", "Unknown Rider"),
            "age": stored.get("age", 25),
            "blood_group": stored.get("bloodGroup") or stored.get("blood_group", "Unknown"),
            "verified": True,
            "allergies": [
                {"name": a, "severity": "moderate"} if isinstance(a, str) else a
                for a in stored.get("allergies", [])
            ],
            "prescriptions": [
                {"name": m, "dosage": "", "frequency": ""} if isinstance(m, str) else m
                for m in (stored.get("medications") or stored.get("prescriptions") or [])
            ],
            "emergency_contacts": [
                {
                    "name": c.get("name", "Contact"),
                    "relation": c.get("relationship") or c.get("relation", "Contact"),
                    "phone": c.get("phone", ""),
                    "is_primary": c.get("isPrimary") or c.get("is_primary", False),
                }
                for c in (stored.get("emergencyContacts") or stored.get("emergency_contacts") or [])
            ],
            "medical_notes": stored.get("emergencyNotes") or stored.get("medical_notes", ""),
            "updated_at": stored.get("updated_at", datetime.now(timezone.utc).isoformat()),
        }
        REGISTERED_PROFILES[user_id] = profile
        return {
            "success": True,
            "source": "supabase_storage",
            "profile": profile,
        }
    except Exception as storage_err:
        logger.debug(f"Profile not found in Supabase storage for {user_id}: {storage_err}")

    # 3. Try fetching from Supabase table 'users'
    try:
        url = os.environ.get("SUPABASE_URL", SUPABASE_URL).strip()
        key = os.environ.get("SUPABASE_SERVICE_KEY", SUPABASE_SERVICE_KEY).strip()

        if url and key:
            client = create_client(url, key)
            response = client.table("users").select("*").eq("id", user_id).execute()

            if response.data and len(response.data) > 0:
                user_data = response.data[0]
                return {
                    "success": True,
                    "source": "supabase",
                    "profile": {
                        "id": user_data.get("id"),
                        "name": user_data.get("name", "Unknown"),
                        "age": user_data.get("age"),
                        "blood_group": user_data.get("blood_group", "Unknown"),
                        "verified": user_data.get("verified", False),
                        "allergies": user_data.get("allergies", []),
                        "prescriptions": user_data.get("prescriptions", []),
                        "emergency_contacts": user_data.get("emergency_contacts", []),
                        "medical_notes": user_data.get("medical_notes", ""),
                        "updated_at": user_data.get("updated_at", datetime.now(timezone.utc).isoformat()),
                    },
                }
    except Exception as e:
        logger.warning(f"Error fetching from Supabase for user {user_id}: {e}")

    # 4. Check predefined mock profiles
    if user_id in MOCK_USER_PROFILES:
        return {
            "success": True,
            "source": "mock",
            "profile": MOCK_USER_PROFILES[user_id],
        }

    # 5. Fallback: generate personalized mock profile with requested ID
    mock = {**MOCK_USER_PROFILE, "id": user_id}
    return {
        "success": True,
        "source": "mock",
        "profile": mock,
    }


@app.get("/api/emergency/{user_id}/exists")
def check_emergency_profile(user_id: str):
    """
    PUBLIC quick-check — verifies whether a user profile exists.
    """
    if user_id in REGISTERED_PROFILES or user_id in MOCK_USER_PROFILES:
        return {"exists": True, "source": "local"}

    try:
        url = os.environ.get("SUPABASE_URL", SUPABASE_URL).strip()
        key = os.environ.get("SUPABASE_SERVICE_KEY", SUPABASE_SERVICE_KEY).strip()

        if url and key:
            client = create_client(url, key)
            response = client.table("users").select("id").eq("id", user_id).execute()
            if response.data and len(response.data) > 0:
                return {"exists": True, "source": "supabase"}
    except Exception as e:
        logger.warning(f"Error checking profile existence: {e}")

    return {"exists": True, "source": "mock"}


@app.get("/api/emergency/{user_id}/prescriptions")
def get_emergency_prescriptions(user_id: str):
    """
    PUBLIC endpoint — returns uploaded prescription files for a user.
    Generates short-lived signed URLs for each PDF so the emergency page can display them.
    Falls back to mock data if Supabase/Firebase is not configured.
    """
    files = []

    # Try Supabase storage + Firestore metadata
    try:
        url = os.environ.get("SUPABASE_URL", SUPABASE_URL).strip()
        key = os.environ.get("SUPABASE_SERVICE_KEY", SUPABASE_SERVICE_KEY).strip()

        if url and key:
            client = create_client(url, key)

            # List files in user's storage folder
            try:
                storage_files = client.storage.from_(BUCKET).list(user_id)
            except Exception:
                storage_files = []

            # Get Firestore metadata if available
            firestore_meta = {}
            try:
                if is_firebase_initialized():
                    db = firestore.client()
                    docs = db.collection("users").document(user_id).collection("files").stream()
                    for doc in docs:
                        firestore_meta[doc.id] = doc.to_dict()
            except Exception as fs_err:
                logger.warning(f"Firestore metadata fetch failed: {fs_err}")

            # Generate signed URLs for each file
            for item in storage_files:
                raw_name = item.get("name", "")
                if not raw_name.endswith(".pdf"):
                    continue
                file_id = raw_name[:-4]
                storage_path = f"{user_id}/{raw_name}"
                meta = firestore_meta.get(file_id, {})

                # Generate a 30-minute signed URL
                try:
                    signed_res = client.storage.from_(BUCKET).create_signed_url(
                        storage_path, expires_in=1800
                    )
                    signed_url = None
                    if isinstance(signed_res, dict):
                        signed_url = signed_res.get("signedURL") or signed_res.get("signedUrl")
                    elif hasattr(signed_res, "signed_url"):
                        signed_url = getattr(signed_res, "signed_url")

                    if signed_url:
                        files.append({
                            "file_id": file_id,
                            "filename": meta.get("originalFileName", raw_name),
                            "signed_url": signed_url,
                            "content_type": "application/pdf",
                            "size": meta.get("size", item.get("metadata", {}).get("size") if isinstance(item.get("metadata"), dict) else 0),
                            "created_at": item.get("created_at") or meta.get("createdAt"),
                            "type": meta.get("type", "medical_prescription"),
                            "doctor": meta.get("doctor"),
                            "clinic": meta.get("clinic"),
                        })
                except Exception as sign_err:
                    logger.warning(f"Could not sign URL for {storage_path}: {sign_err}")

            if files:
                return {
                    "success": True,
                    "source": "supabase",
                    "files": files,
                }

    except Exception as e:
        logger.warning(f"Error fetching prescriptions for user {user_id}: {e}")

    # Fallback: check profile-specific mock prescription files
    if user_id in MOCK_USER_PRESCRIPTIONS:
        return {
            "success": True,
            "source": "mock",
            "files": MOCK_USER_PRESCRIPTIONS[user_id],
        }

    # Default fallback
    return {
        "success": True,
        "source": "mock",
        "files": MOCK_USER_PRESCRIPTIONS.get("demo-user-001", []),
    }


@app.get("/api/hospitals/nearby")
async def get_nearby_hospitals(
    lat: float = Query(..., description="Latitude of the crash/scan location"),
    lng: float = Query(..., description="Longitude of the crash/scan location"),
    radius: int = Query(5000, description="Search radius in meters (default 5000)"),
):
    """
    PUBLIC endpoint — returns nearby hospitals.
    Uses Google Places API if GOOGLE_MAPS_API_KEY is set, otherwise returns mock data
    with distances calculated from the provided coordinates.
    """
    google_api_key = os.environ.get("GOOGLE_MAPS_API_KEY", "").strip()

    if google_api_key:
        # Use Google Places Nearby Search
        try:
            places_url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
            params = {
                "location": f"{lat},{lng}",
                "radius": radius,
                "type": "hospital",
                "key": google_api_key,
            }
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(places_url, params=params)
                data = resp.json()

            hospitals = []
            for place in data.get("results", [])[:8]:
                p_lat = place.get("geometry", {}).get("location", {}).get("lat", 0)
                p_lng = place.get("geometry", {}).get("location", {}).get("lng", 0)
                dist = _haversine_km(lat, lng, p_lat, p_lng)

                hospitals.append({
                    "name": place.get("name", "Unknown Hospital"),
                    "address": place.get("vicinity", ""),
                    "distance_km": round(dist, 1),
                    "open_now": place.get("opening_hours", {}).get("open_now", None),
                    "phone": None,  # Places Nearby doesn't return phone; would need Details API
                    "lat": p_lat,
                    "lng": p_lng,
                    "rating": place.get("rating"),
                    "maps_url": f"https://www.google.com/maps/dir/?api=1&destination={p_lat},{p_lng}&travelmode=driving",
                })

            hospitals.sort(key=lambda h: h["distance_km"])
            return {
                "success": True,
                "source": "google_places",
                "hospitals": hospitals,
            }

        except Exception as e:
            logger.error(f"Google Places API error: {e}")

    # Fallback: mock hospitals with recalculated distances
    hospitals = []
    for h in MOCK_HOSPITALS:
        dist = _haversine_km(lat, lng, h["lat"], h["lng"])
        hospitals.append({
            **h,
            "distance_km": round(dist, 1),
            "maps_url": f"https://www.google.com/maps/dir/?api=1&destination={h['lat']},{h['lng']}&travelmode=driving",
        })

    hospitals.sort(key=lambda h: h["distance_km"])
    return {
        "success": True,
        "source": "mock",
        "hospitals": hospitals,
    }


# ---------------------------------------------------------------------------
# Serve Frontend Static Files (mount LAST so it doesn't override API routes)
# ---------------------------------------------------------------------------
_frontend_dir = os.path.join(os.path.dirname(__file__), "..", "Frontend")
if os.path.isdir(_frontend_dir):
    app.mount("/", StaticFiles(directory=_frontend_dir, html=True), name="frontend")
    logger.info(f"Serving frontend from: {_frontend_dir}")
