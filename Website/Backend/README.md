# ResQRide Backend API

FastAPI service powering secure PDF document upload, private Supabase Storage, and emergency medical record access for the ResQRide ecosystem.

---

## 1. Architecture Overview

- **Identity Provider**: Firebase Authentication (Bearer Token verified server-side with Firebase Admin SDK).
- **File Storage**: Supabase Private Bucket named `pdfs`.
- **Application Metadata**: Firebase Firestore (`users/{uid}/files/{file_id}`).
- **Proxy Server**: FastAPI (deployed on Render, running Python with `uv`).
- **Security Rule**: The Android application never accesses Supabase credentials directly. All operations strictly verify the client's Firebase ID token and derive file ownership from the authenticated UID.

---

## 2. Environment Variables

Configure these environment variables in your Render service dashboard:

| Variable | Description | Example |
| :--- | :--- | :--- |
| `SUPABASE_URL` | Your Supabase project URL | `https://fxmyholhnknltmbusvds.supabase.co` |
| `SUPABASE_SERVICE_KEY` | Supabase `service_role` secret key | `eyJhbGciOi...` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Admin service account JSON string | `{"type": "service_account", ...}` |
| `ALLOWED_ORIGINS` | (Optional) Comma-separated list of allowed CORS origins | `https://resqride-oqhy.onrender.com,http://localhost:3000` |

> **Security Note**: Never commit actual credentials or `.env` files to git. Render environment variables are encrypted and provided at runtime.

---

## 3. Render Deployment Settings

Configure the Web Service on [Render](https://render.com/):

- **Root Directory**: `Website/Backend`
- **Environment**: `Python`
- **Build Command**:
  ```bash
  pip install uv && uv sync --frozen
  ```
- **Start Command**:
  ```bash
  uv run uvicorn main:app --host 0.0.0.0 --port $PORT
  ```
- **Health Check Path**: `/health`

---

## 4. Local Development

Ensure Python 3.13+ and `uv` are installed.

```bash
# Navigate to backend directory
cd Website/Backend

# Install dependencies and sync virtual environment
uv sync

# Run the test suite
uv run pytest

# Start local development server
uv run uvicorn main:app --reload --host 127.0.0.1 --port 8000
```

---

## 5. API Endpoints

### Public Endpoints

#### `GET /health`
Verifies backend status and configuration.
- **Response**:
  ```json
  {
    "status": "ok",
    "firebase_configured": true,
    "supabase_configured": true
  }
  ```

#### `GET /`
Basic root status.
- **Response**: `{"message": "ResQRide backend is running", ...}`

---

### Protected File Endpoints
All protected endpoints require the Firebase ID token in the HTTP Authorization header:
```http
Authorization: Bearer <FIREBASE_ID_TOKEN>
```

#### `POST /api/files/upload`
Uploads a single PDF file into the user's private folder (`{firebase_uid}/{uuid}.pdf`).
- **Headers**:
  - `Authorization: Bearer <FIREBASE_ID_TOKEN>`
  - `Content-Type: multipart/form-data`
- **Form Data**:
  - `file`: Binary PDF file (MIME `application/pdf`, starts with `%PDF-`, max 10 MB)
- **Response**:
  ```json
  {
    "success": true,
    "file_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "original_filename": "medical_prescription.pdf",
    "storage_path": "firebase_uid_abc/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d.pdf",
    "content_type": "application/pdf",
    "size": 124500
  }
  ```

#### `GET /api/files`
Lists all uploaded files belonging to the authenticated user.
- **Response**:
  ```json
  {
    "success": true,
    "files": [
      {
        "file_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
        "filename": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d.pdf",
        "original_filename": "medical_prescription.pdf",
        "storage_path": "firebase_uid_abc/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d.pdf",
        "content_type": "application/pdf",
        "size": 124500,
        "created_at": "2026-09-15T02:00:00Z",
        "type": "medical_prescription"
      }
    ]
  }
  ```

#### `GET /api/files/{file_id}`
Generates a short-lived (10-minute) signed URL to download or view a private file.
- **Response**:
  ```json
  {
    "success": true,
    "file_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "signed_url": "https://xyz.supabase.co/storage/v1/object/sign/pdfs/...?token=...",
    "expires_in": 600
  }
  ```

#### `DELETE /api/files/{file_id}`
Deletes the file from Supabase storage and removes Firestore metadata.
- **Response**:
  ```json
  {
    "success": true,
    "message": "File deleted successfully",
    "file_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  }
  ```

---

### Rider Profile & Emergency Triage Endpoints

#### `POST /api/profile`
Synchronizes user profile, medical history, emergency contacts, and prescription metadata to Supabase (`profiles/{uid}.json`).
- **Body**:
  ```json
  {
    "uid": "user_12345",
    "fullName": "Rahul Sharma",
    "email": "rahul@example.com",
    "phone": "+91 9876543210",
    "age": 26,
    "gender": "Male",
    "bloodGroup": "O+",
    "allergies": ["Penicillin"],
    "chronicConditions": ["Asthma"],
    "emergencyNotes": "Carry inhaler",
    "emergencyContacts": [
      {
        "name": "Anil Sharma",
        "phone": "+91 9876500000",
        "relationship": "Father",
        "isPrimary": true
      }
    ],
    "prescriptionFileName": "prescription.pdf",
    "prescriptionFileId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "publicEmergencyUrl": "https://resqride-oqhy.onrender.com/med/user_12345"
  }
  ```
- **Response**:
  ```json
  {
    "success": true,
    "message": "User profile synchronized to Supabase successfully",
    "user_id": "user_12345",
    "public_url": "https://resqride-oqhy.onrender.com/med/user_12345"
  }
  ```

#### `GET /api/profile/{user_id}`
Retrieves the saved user profile from Supabase.
- **Response**: `{"success": true, "profile": { ... }}`

#### `GET /med/{user_id}`
**Dynamic First Responder Emergency Medical Triage Web Card**.
- Scanned directly from the physical QR code on the victim's helmet.
- Renders an ultra-fast, mobile-optimized HTML triage page featuring:
  - 🚨 Emergency Triage Banner
  - Prominent Blood Group Badge (e.g., O+, B-)
  - Highlighted Allergies & Chronic Conditions tags
  - Emergency Instructions / Physician notes
  - One-tap Emergency Contact buttons (`tel:+91...`)
  - Direct signed links to view/download verified prescription PDFs from Supabase Storage
- URL: `https://resqride-oqhy.onrender.com/med/{user_id}`

#### `GET /api/med/{user_id}`
JSON representation of the emergency medical card with signed PDF access URLs.

---

## 6. Supabase Storage Configuration

1. Log in to [Supabase Console](https://supabase.com/dashboard).
2. Open **Storage** -> **New Bucket**.
3. Bucket 1: `pdfs` (Private bucket for prescription and medical documents).
4. Bucket 2: `profiles` (Public bucket for JSON profile persistence and rapid triage retrieval).
5. All operations are mediated via the backend using the `service_role` key, ensuring verified uploads and signed emergency document downloads.

