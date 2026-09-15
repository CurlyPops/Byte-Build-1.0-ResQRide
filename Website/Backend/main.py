import base64
from datetime import datetime, timezone
import json
import logging
import os
from typing import Optional, List, Dict, Any
import uuid
from fastapi import Depends, FastAPI, File, HTTPException, Security, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import firebase_admin
from firebase_admin import auth, credentials, firestore
from pydantic import BaseModel, Field
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
    for tbl in ["user_profiles", "profiles"]:
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
    Retrieves stored profile JSON from Supabase.
    """
    clean_uid = os.path.basename(user_id).strip()
    if not clean_uid:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid user ID.")

    client = get_supabase_client()
    try:
        data_bytes = client.storage.from_(PROFILES_BUCKET).download(f"{clean_uid}.json")
        profile_data = json.loads(data_bytes.decode("utf-8"))
        return {"success": True, "profile": profile_data}
    except Exception as e:
        logger.warning(f"Profile not found in Supabase storage for {clean_uid}: {e}")
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Profile not found.")


# ---------------------------------------------------------------------------
# Dynamic First Responder Emergency Medical Triage Endpoints
# ---------------------------------------------------------------------------
def _get_user_medical_data_with_pdfs(user_id: str) -> dict:
    """
    Fetches user profile and queries Supabase Storage for all uploaded prescription PDFs,
    generating signed download URLs.
    """
    client = get_supabase_client()
    clean_uid = os.path.basename(user_id).strip()

    profile_data = {}
    try:
        data_bytes = client.storage.from_(PROFILES_BUCKET).download(f"{clean_uid}.json")
        profile_data = json.loads(data_bytes.decode("utf-8"))
    except Exception as e:
        logger.warning(f"Could not load profile from Supabase for {clean_uid}: {e}")

    # Query uploaded PDFs in Supabase storage 'pdfs/{clean_uid}/'
    pdf_docs = []
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
    contacts = profile.get("emergencyContacts") or []

    if allergies:
        allergies_html = "".join([f'<span class="tag tag-red">{a}</span>' for a in allergies])
    else:
        allergies_html = '<span class="tag tag-gray">No Known Drug Allergies (NKDA)</span>'

    if conditions:
        conditions_html = "".join([f'<span class="tag tag-amber">{c}</span>' for c in conditions])
    else:
        conditions_html = '<span class="tag tag-gray">None Reported</span>'

    contacts_html = ""
    for idx, c in enumerate(contacts):
        c_name = c.get("name", "Emergency Contact")
        c_rel = c.get("relationship", "Contact")
        c_phone = c.get("phone", "")
        clean_p = c_phone.replace(" ", "").replace("-", "")
        is_prim = c.get("isPrimary", False) or idx == 0
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

        <div class="footer">
            ResQRide Universal Smart Emergency Response<br>
            Hosted on <a href="https://resqride-oqhy.onrender.com" style="color: #60a5fa; text-decoration: none;">https://resqride-oqhy.onrender.com</a>
        </div>
    </div>
</body>
</html>"""
    return HTMLResponse(content=html_content, status_code=200)