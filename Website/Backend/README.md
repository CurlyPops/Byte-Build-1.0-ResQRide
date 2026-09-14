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
| `SUPABASE_URL` | Your Supabase project URL | `https://xyzproject.supabase.co` |
| `SUPABASE_SERVICE_KEY` | Supabase `service_role` secret key | `eyJhbGciOi...` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Admin service account JSON string (or Base64-encoded) | `{"type": "service_account", ...}` |
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

## 6. Supabase Storage Configuration

1. Log in to [Supabase Console](https://supabase.com/dashboard).
2. Open **Storage** -> **New Bucket**.
3. Bucket Name: `pdfs`
4. Set **Public Bucket**: **DISABLED (Private)**.
5. Do not enable public access policies. All access is performed through the backend with the `service_role` key, ensuring that only verified Firebase users can obtain short-lived signed URLs for their own files.
