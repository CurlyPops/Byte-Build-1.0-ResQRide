package com.aurafarmers.resqride.medical

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MedicalManager(private val context: Context) {

    /**
     * Prints the user's prescription PDF directly via Android PrintManager.
     */
    fun printPrescription(pdfFile: File) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(context, "Print service unavailable on this device", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val printAdapter = PdfPrintDocumentAdapter(pdfFile)
            val printAttributes = PrintAttributes.Builder().build()

            printManager.print("ResQRide_Medical_Prescription", printAdapter, printAttributes)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to send to printer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shares or exports the prescription PDF to other apps (WhatsApp, Drive, Email, etc.)
     */
    fun sharePrescription(pdfFile: File) {
        try {
            val fileUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Medical Prescription - ResQRide Emergency Record")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Prescription PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Creates a sample demo medical prescription PDF for testing when no file has been uploaded yet.
     */
    fun getOrCreateDemoPrescription(): File {
        val file = File(context.filesDir, "sample_prescription.pdf")
        if (!file.exists()) {
            try {
                FileOutputStream(file).use { out ->
                    out.write("%PDF-1.4\n%ResQRide Medical Prescription Record\n%%EOF".toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return file
    }
}
