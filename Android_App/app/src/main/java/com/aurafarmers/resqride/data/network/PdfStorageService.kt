package com.aurafarmers.resqride.data.network

import android.content.Context
import android.net.Uri
import com.aurafarmers.resqride.auth.FirebaseAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PdfUploadResult(
    val fileId: String,
    val originalFilename: String,
    val storagePath: String,
    val size: Long
)

data class PdfFileItem(
    val fileId: String,
    val filename: String,
    val originalFilename: String,
    val storagePath: String,
    val size: Long,
    val createdAt: String?,
    val type: String?
)

class PdfStorageService(
    private val context: Context,
    private val authManager: FirebaseAuthManager = FirebaseAuthManager(context),
    private val baseUrl: String = ApiConfig.BASE_URL
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads a local PDF Uri to FastAPI /api/files/upload with Bearer token authentication.
     */
    suspend fun uploadPdf(fileUri: Uri, originalFilename: String): Result<PdfUploadResult> =
        withContext(Dispatchers.IO) {
            try {
                val token = authManager.getIdToken()
                    ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Firebase."))

                val contentResolver = context.contentResolver

                // Read file stream safely with 10MB limit enforcement
                val inputStream = contentResolver.openInputStream(fileUri)
                    ?: return@withContext Result.failure(IOException("Unable to open selected file."))

                val byteBuffer = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var totalBytesRead = 0
                val maxAllowedBytes = 10 * 1024 * 1024 // 10 MB

                inputStream.use { stream ->
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesRead += bytesRead
                        if (totalBytesRead > maxAllowedBytes) {
                            return@withContext Result.failure(
                                IllegalArgumentException("Selected PDF exceeds 10 MB maximum allowed size.")
                            )
                        }
                        byteBuffer.write(buffer, 0, bytesRead)
                    }
                }

                val fileBytes = byteBuffer.toByteArray()
                if (fileBytes.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("Selected PDF is empty."))
                }

                // Verify magic bytes (%PDF-)
                val pdfHeader = "%PDF-".toByteArray(Charsets.US_ASCII)
                val isValidPdfHeader = fileBytes.size >= pdfHeader.size &&
                    pdfHeader.indices.all { fileBytes[it] == pdfHeader[it] }

                if (!isValidPdfHeader) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid PDF format. File does not begin with %PDF- header.")
                    )
                }

                val mediaType = "application/pdf".toMediaType()
                val requestFileBody = fileBytes.toRequestBody(mediaType)

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", originalFilename, requestFileBody)
                    .build()

                val requestUrl = "${baseUrl.trimEnd('/')}/api/files/upload"

                val request = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .post(multipartBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBodyStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(responseBodyStr).optString("detail", "Upload failed (${response.code})")
                    } catch (e: Exception) {
                        "Upload failed (${response.code})"
                    }
                    return@withContext Result.failure(IOException(errorMsg))
                }

                val json = JSONObject(responseBodyStr)
                val result = PdfUploadResult(
                    fileId = json.optString("file_id"),
                    originalFilename = json.optString("original_filename", originalFilename),
                    storagePath = json.optString("storage_path"),
                    size = json.optLong("size", fileBytes.size.toLong())
                )

                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Lists uploaded PDFs belonging to the authenticated Firebase user.
     */
    suspend fun listFiles(): Result<List<PdfFileItem>> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getIdToken()
                ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Firebase."))

            val requestUrl = "${baseUrl.trimEnd('/')}/api/files"
            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBodyStr).optString("detail", "Failed to list files (${response.code})")
                } catch (e: Exception) {
                    "Failed to list files (${response.code})"
                }
                return@withContext Result.failure(IOException(errorMsg))
            }

            val json = JSONObject(responseBodyStr)
            val filesArray = json.optJSONArray("files") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<PdfFileItem>()
            for (i in 0 until filesArray.length()) {
                val item = filesArray.getJSONObject(i)
                list.add(
                    PdfFileItem(
                        fileId = item.optString("file_id"),
                        filename = item.optString("filename"),
                        originalFilename = item.optString("original_filename"),
                        storagePath = item.optString("storage_path"),
                        size = item.optLong("size", 0L),
                        createdAt = item.optString("created_at").takeIf { it.isNotEmpty() },
                        type = item.optString("type").takeIf { it.isNotEmpty() }
                    )
                )
            }

            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves a short-lived (10 min) signed URL to view or download the user's PDF.
     */
    suspend fun getSignedUrl(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getIdToken()
                ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Firebase."))

            val requestUrl = "${baseUrl.trimEnd('/')}/api/files/$fileId"
            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBodyStr).optString("detail", "Failed to retrieve URL (${response.code})")
                } catch (e: Exception) {
                    "Failed to retrieve file URL (${response.code})"
                }
                return@withContext Result.failure(IOException(errorMsg))
            }

            val json = JSONObject(responseBodyStr)
            val signedUrl = json.optString("signed_url")
            if (signedUrl.isNullOrBlank()) {
                return@withContext Result.failure(IOException("Server returned empty signed URL."))
            }

            Result.success(signedUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes a user's PDF from storage and metadata.
     */
    suspend fun deletePdf(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getIdToken()
                ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Firebase."))

            val requestUrl = "${baseUrl.trimEnd('/')}/api/files/$fileId"
            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBodyStr).optString("detail", "Failed to delete file (${response.code})")
                } catch (e: Exception) {
                    "Failed to delete file (${response.code})"
                }
                return@withContext Result.failure(IOException(errorMsg))
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
