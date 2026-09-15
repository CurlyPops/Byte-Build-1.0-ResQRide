from io import BytesIO
import json
from unittest.mock import MagicMock, patch
import pytest
from fastapi.testclient import TestClient

from main import app, get_current_user


@pytest.fixture
def client():
    return TestClient(app)


def test_health_endpoint(client):
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "firebase_configured" in data
    assert "supabase_configured" in data


def test_root_endpoint(client):
    response = client.get("/")
    assert response.status_code == 200
    assert "ResQRide backend is running" in response.json()["message"]


def test_protected_endpoints_reject_unauthenticated(client):
    # Missing Bearer token
    assert client.get("/api/files").status_code == 401
    assert client.get("/api/files/test-id").status_code == 401
    assert client.delete("/api/files/test-id").status_code == 401
    assert client.post("/api/files/upload").status_code == 401


def test_protected_endpoints_reject_invalid_token(client):
    # Invalid Bearer token
    headers = {"Authorization": "Bearer invalid.token.payload"}
    response = client.get("/api/files", headers=headers)
    assert response.status_code == 401


def test_upload_rejects_non_pdf_mime(client):
    # Override get_current_user to simulate an authenticated user
    app.dependency_overrides[get_current_user] = lambda: {"uid": "test-user-123"}
    try:
        files = {"file": ("test.txt", b"Hello world", "text/plain")}
        response = client.post("/api/files/upload", files=files)
        assert response.status_code == 400
        assert "Only PDF files are allowed" in response.json()["detail"]
    finally:
        app.dependency_overrides.clear()


def test_upload_rejects_invalid_magic_bytes(client):
    app.dependency_overrides[get_current_user] = lambda: {"uid": "test-user-123"}
    try:
        # Correct MIME type but invalid PDF magic bytes (fake PDF)
        files = {"file": ("fake.pdf", b"NOT_A_REAL_PDF_CONTENT", "application/pdf")}
        response = client.post("/api/files/upload", files=files)
        assert response.status_code == 400
        assert "missing %PDF- header" in response.json()["detail"]
    finally:
        app.dependency_overrides.clear()


def test_upload_rejects_empty_file(client):
    app.dependency_overrides[get_current_user] = lambda: {"uid": "test-user-123"}
    try:
        files = {"file": ("empty.pdf", b"", "application/pdf")}
        response = client.post("/api/files/upload", files=files)
        assert response.status_code == 400
        assert "empty" in response.json()["detail"].lower()
    finally:
        app.dependency_overrides.clear()


def test_upload_rejects_oversized_file(client):
    app.dependency_overrides[get_current_user] = lambda: {"uid": "test-user-123"}
    try:
        # Create a dummy payload slightly larger than 10 MB
        oversized = b"%PDF-1.4" + (b"0" * (10 * 1024 * 1024 + 10))
        files = {"file": ("large.pdf", oversized, "application/pdf")}
        response = client.post("/api/files/upload", files=files)
        assert response.status_code == 400
        assert "smaller than 10 MB" in response.json()["detail"]
    finally:
        app.dependency_overrides.clear()


def test_upload_valid_pdf_success(client):
    app.dependency_overrides[get_current_user] = lambda: {"uid": "test-user-123"}
    mock_supabase = MagicMock()
    mock_supabase.storage.from_().upload.return_value = {"Key": "test-user-123/sample.pdf"}

    try:
        with patch("main.get_supabase_client", return_value=mock_supabase):
            valid_pdf = b"%PDF-1.4\n%ResQRide Prescription Sample\n%%EOF"
            files = {"file": ("prescription.pdf", valid_pdf, "application/pdf")}
            response = client.post("/api/files/upload", files=files)

            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True
            assert data["storage_path"].startswith("test-user-123/")
            assert data["original_filename"] == "prescription.pdf"
            assert data["size"] == len(valid_pdf)
    finally:
        app.dependency_overrides.clear()


def test_file_operations_sanitize_path_traversal(client):
    app.dependency_overrides[get_current_user] = lambda: {"uid": "test-user-123"}
    try:
        # Invalid file IDs containing traversal or illegal characters
        response = client.get("/api/files/..test")
        assert response.status_code == 400

        response = client.delete("/api/files/..test")
        assert response.status_code == 400

        response = client.get("/api/files/bad\\name")
        assert response.status_code == 400
    finally:
        app.dependency_overrides.clear()


def test_get_signed_url_success(client):
    app.dependency_overrides[get_current_user] = lambda: {"uid": "user-456"}
    mock_supabase = MagicMock()
    mock_supabase.storage.from_().create_signed_url.return_value = {
        "signedURL": "https://xyz.supabase.co/storage/v1/object/sign/pdfs/user-456/doc.pdf?token=abc"
    }

    try:
        with patch("main.get_supabase_client", return_value=mock_supabase):
            response = client.get("/api/files/doc123")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True
            assert "signed_url" in data
            assert data["expires_in"] == 600
    finally:
        app.dependency_overrides.clear()


def test_delete_file_success(client):
    app.dependency_overrides[get_current_user] = lambda: {"uid": "user-456"}
    mock_supabase = MagicMock()
    mock_supabase.storage.from_().remove.return_value = [{"name": "user-456/doc123.pdf"}]

    try:
        with patch("main.get_supabase_client", return_value=mock_supabase):
            response = client.delete("/api/files/doc123")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True
            assert data["file_id"] == "doc123"
    finally:
        app.dependency_overrides.clear()


def test_save_user_profile_success(client):
    mock_supabase = MagicMock()
    mock_supabase.storage.from_().upload.return_value = {"Key": "profiles/test_rider_1.json"}

    payload = {
        "uid": "test_rider_1",
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
                "isPrimary": True
            }
        ]
    }

    with patch("main.get_supabase_client", return_value=mock_supabase):
        response = client.post("/api/profile", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["user_id"] == "test_rider_1"
        assert "https://resqride-oqhy.onrender.com/med/test_rider_1" in data["public_url"]


def test_get_user_profile_success(client):
    mock_supabase = MagicMock()
    sample_profile = {
        "uid": "test_rider_1",
        "fullName": "Rahul Sharma",
        "bloodGroup": "O+",
        "allergies": ["Penicillin"]
    }
    mock_supabase.storage.from_().download.return_value = json.dumps(sample_profile).encode()

    with patch("main.get_supabase_client", return_value=mock_supabase):
        response = client.get("/api/profile/test_rider_1")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["profile"]["fullName"] == "Rahul Sharma"
        assert data["profile"]["bloodGroup"] == "O+"


def test_get_user_profile_not_found(client):
    mock_supabase = MagicMock()
    mock_supabase.storage.from_().download.side_effect = Exception("Object not found")

    with patch("main.get_supabase_client", return_value=mock_supabase):
        response = client.get("/api/profile/non_existent_rider")
        assert response.status_code == 404


def test_dynamic_medical_triage_html(client):
    mock_supabase = MagicMock()
    sample_profile = {
        "uid": "rider_999",
        "fullName": "Amit Verma",
        "phone": "+91 9998887770",
        "age": 28,
        "gender": "Male",
        "bloodGroup": "B+",
        "allergies": ["Aspirin"],
        "chronicConditions": ["Type 1 Diabetes"],
        "emergencyNotes": "Insulin dependent",
        "emergencyContacts": [
            {
                "name": "Pooja Verma",
                "phone": "+91 9998887771",
                "relationship": "Spouse",
                "isPrimary": True
            }
        ]
    }
    mock_supabase.storage.from_().download.return_value = json.dumps(sample_profile).encode()
    mock_supabase.storage.from_().list.return_value = [
        {"name": "prescription_scan.pdf"}
    ]
    mock_supabase.storage.from_().create_signed_url.return_value = {
        "signedURL": "https://fxmyholhnknltmbusvds.supabase.co/storage/v1/object/sign/pdfs/rider_999/prescription_scan.pdf?token=dummy"
    }

    with patch("main.get_supabase_client", return_value=mock_supabase):
        # 1. Test HTML endpoint
        res_html = client.get("/med/rider_999")
        assert res_html.status_code == 200
        assert "text/html" in res_html.headers.get("content-type", "")
        content = res_html.text
        assert "Amit Verma" in content
        assert "B+" in content
        assert "Aspirin" in content
        assert "Type 1 Diabetes" in content
        assert "Pooja Verma" in content
        assert "prescription_scan.pdf" in content
        assert "https://resqride-oqhy.onrender.com" in content

        # 2. Test JSON API endpoint
        res_json = client.get("/api/med/rider_999")
        assert res_json.status_code == 200
        j_data = res_json.json()
        assert j_data["success"] is True
        assert j_data["data"]["profile"]["fullName"] == "Amit Verma"
        assert len(j_data["data"]["prescription_documents"]) == 1


def test_dynamic_medical_triage_html_not_found(client):
    mock_supabase = MagicMock()
    mock_supabase.storage.from_().download.side_effect = Exception("File not found")
    mock_supabase.storage.from_().list.return_value = []

    with patch("main.get_supabase_client", return_value=mock_supabase):
        res_html = client.get("/med/unknown_rider_id")
        assert res_html.status_code == 200
        assert "Rider Profile Pending Sync" in res_html.text
        assert "unknown_rider_id" in res_html.text

