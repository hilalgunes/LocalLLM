package com.hilal.llm.rag

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagDocumentReaderTest {
    @Test fun pdfContentIsReadInPageOrderAndInvalidPdfFails() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("rag-reader-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                listOf("First page", "Second page").forEach { text ->
                    val page = PDPage()
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(20f, 700f)
                        content.showText(text)
                        content.endText()
                    }
                }
                document.save(file)
            }
            val text = RagDocumentReader.read(context.contentResolver, Uri.fromFile(file), "document.PDF")
            assertTrue(text.contains("First page"))
            assertTrue(text.contains("Second page"))
            assertTrue(text.indexOf("First page") < text.indexOf("Second page"))
            file.writeText("not a PDF")
            assertTrue(runCatching { RagDocumentReader.read(context.contentResolver, Uri.fromFile(file), file.name) }.isFailure)
        } finally { file.delete() }
    }
}
