package com.hilal.llm.rag

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns document IO and PDF parsing; does not index or embed text. */
object RagDocumentReader {
    suspend fun read(contentResolver: ContentResolver, uri: Uri, displayName: String): String =
        withContext(Dispatchers.IO) {
            val mimeType = contentResolver.getType(uri)
            contentResolver.openInputStream(uri)?.use { input ->
                parse(input, displayName, mimeType)
            } ?: throw IllegalStateException("Unable to read document: $displayName")
        }

    internal fun isPdf(displayName: String, mimeType: String?): Boolean =
        displayName.endsWith(".pdf", ignoreCase = true) ||
            mimeType.equals("application/pdf", ignoreCase = true)

    // The caller owns the input stream. Parsing failures propagate to the import flow.
    internal fun parse(input: InputStream, displayName: String, mimeType: String?): String =
        if (isPdf(displayName, mimeType)) {
            PDDocument.load(input).use { PDFTextStripper().getText(it).trim() }
        } else {
            input.bufferedReader(Charsets.UTF_8).readText().trim()
        }
}
