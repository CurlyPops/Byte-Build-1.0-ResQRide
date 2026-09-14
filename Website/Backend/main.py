import base64
from datetime import datetime, timezone
import json
import logging
import os
from typing import Optional
import uuid

from fastapi import Depends, FastAPI, File, HTTPException, Security, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import firebase_admin
from firebase_admin import auth, credentials, firestore
from supabase import Client, create_client

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("resqride-backend")

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
        else:
            # Fallback to Google Application Default Credentials if available
            try:
                firebase_admin.initialize_app()
                _firebase_initialized = True
                logger.info("Firebase Admin initialized via Application Default Credentials.")
            except Exception as default_err:
                logger.warning(
                    f"Firebase credentials not found or unconfigured: {default_err}. "
                    "Protected endpoints requiring Firebase ID token verification will fail until credentials are provided."
                )
    except Exception as e:
        logger.error(f"Failed to initialize Firebase Admin SDK: {e}")


init_firebase()


def is_firebase_initialized() -> bool:
    return _firebase_initialized or bool(firebase_admin._apps)


# ---------------------------------------------------------------------------
# Supabase Client Initialization
# ---------------------------------------------------------------------------
SUPABASE_URL = os.environ.get("SUPABASE_URL", "").strip()
SUPABASE_SERVICE_KEY = os.environ.get("SUPABASE_SERVICE_KEY", "").strip()
BUCKET = "pdfs"


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
            logger.error("Authentication rejected: Firebase Admin is not initialized.")
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Authentication service misconfigured on server.",
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


# ---------------------------------------------------------------------------
# Public Endpoints
# ---------------------------------------------------------------------------
@app.get("/")
def root():
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