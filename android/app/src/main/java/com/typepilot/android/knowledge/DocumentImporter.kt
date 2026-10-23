package com.typepilot.android.knowledge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.zip.ZipInputStream

/** Reads the text out of PDF, Word (.docx), text, Markdown, CSV and JSON files. */
object DocumentImporter {
    data class Result(val name: String, val mime: String, val text: String)

    fun read(context: Context, uri: Uri): Result {
        val cr = context.contentResolver
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        val mime = cr.getType(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        val text = when {
            mime == "application/pdf" || ext == "pdf" -> {
                PDFBoxResourceLoader.init(context.applicationContext)
                cr.openInputStream(uri)!!.use { input -> PDDocument.load(input).use { doc -> PDFTextStripper().getText(doc) } }
            }
            ext == "docx" || mime.contains("wordprocessingml") -> cr.openInputStream(uri)!!.use { docxText(ZipInputStream(it)) }
            else -> cr.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return Result(name, mime.ifBlank { ext }, text.replace("\r", "").replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim())
    }

    /** .docx is a zip; the text lives in word/document.xml (<w:t> runs, <w:p> paragraphs). */
    private fun docxText(zip: ZipInputStream): String {
        while (true) {
            val e = zip.nextEntry ?: return ""
            if (e.name == "word/document.xml") {
                val xml = zip.readBytes().toString(Charsets.UTF_8)
                return xml.replace(Regex("""</w:p>"""), "\n").replace(Regex("""<w:tab/>"""), "\t").replace(Regex("""<w:br/>"""), "\n")
                    .replace(Regex("""<[^>]+>"""), "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            }
        }
    }
}
