package com.hilal.llm.rag

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlin.math.sqrt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val tokenizerRegex = Regex("[^\\p{L}\\p{N}]+")
private val stopWords = setOf(
    "ve", "veya", "ile", "bir", "bu", "şu", "şey", "için", "ama",
    "the", "and", "or", "to", "of", "in", "is", "are", "on", "for"
)

suspend fun readDocumentText(
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String
): String = withContext(Dispatchers.IO) {
    val normalizedName = displayName.lowercase()
    val isPdf = normalizedName.endsWith(".pdf") ||
        (contentResolver.getType(uri)?.equals("application/pdf", ignoreCase = true) == true)

    if (isPdf) {
        contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { document ->
                PDFTextStripper().getText(document).trim()
            }
        } ?: throw IllegalStateException("Unable to read PDF document: $displayName")
    } else {
        contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText().trim() }
        } ?: throw IllegalStateException("Unable to read text document: $displayName")
    }
}

fun buildChunksFromDocument(documentId: String, displayName: String, text: String): List<RagChunk> {
    val chunks = chunkText(text)
    return chunks.mapIndexed { index, chunk ->
        RagChunk(
            documentId = documentId,
            documentName = displayName,
            chunkIndex = index,
            text = chunk
        )
    }
}

fun chunkText(
    text: String,
    chunkSize: Int = 900,
    overlap: Int = 150
): List<String> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return emptyList()
    if (normalized.length <= chunkSize) return listOf(normalized)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < normalized.length) {
        val end = (start + chunkSize).coerceAtMost(normalized.length)
        val chunk = normalized.substring(start, end).trim()
        if (chunk.isNotBlank()) {
            chunks.add(chunk)
        }
        if (end >= normalized.length) break
        start = (end - overlap).coerceAtLeast(start + 1)
    }
    return chunks
}

suspend fun rankChunks(
    question: String,
    chunks: List<RagChunk>,
    embeddingModelPath: String,
    embeddingProvider: RagEmbeddingProvider,
    limit: Int = 4
): List<RagChunk> {
    if (chunks.isEmpty()) return emptyList()

    val questionVector = embeddingProvider.embed(question, embeddingModelPath)
    return chunks
        .map { chunk ->
            val chunkVector = embeddingProvider.embed(chunk.text, embeddingModelPath)
            val score = cosineSimilarity(questionVector, chunkVector)
            chunk to score
        }
        .sortedWith(compareByDescending<Pair<RagChunk, Double>> { it.second }
            .thenBy { it.first.documentName }
            .thenBy { it.first.chunkIndex })
        .map { it.first }
        .take(limit)
}

fun buildRagPrompt(question: String, chunks: List<RagChunk>): String {
    val context = if (chunks.isEmpty()) {
        "Belge bağlamı bulunamadı."
    } else {
        chunks.joinToString(separator = "\n\n") { chunk ->
            buildString {
                append('[')
                append(chunk.documentName)
                append(" / Parça ")
                append(chunk.chunkIndex + 1)
                append("]\n")
                append(chunk.text.trim())
            }
        }
    }

    return """
        Sen bir belge soru-cevap asistanısın.
        Cevabı yalnızca aşağıdaki belge bağlamını kullanarak ver.
        Cevabın tamamını Türkçe yaz.
        İngilizce kelimeler, cümleler, başlıklar veya etiketler kullanma.
        Bilgi belgelerde yoksa bunu açıkça söyle.
        Belgedeki ifadeyi uydurma, tahmin etme veya genelleme yapma.
        Cevap kısa ve doğrudan olsun.
        Başlık, madde işareti, numaralandırma, "Cevap:" etiketi, düşünme metni ve kaynak notu yazma.

        Belge bağlamı:
        $context

        Soru:
        ${question.trim()}
    """.trimIndent()
}

data class StrictRagAnswer(
    val answer: String,
    val sourceChunks: List<RagChunk>,
    val answerMode: String = "Kurallı çıkarım"
)

fun buildStrictRagAnswer(question: String, chunks: List<RagChunk>): StrictRagAnswer? {
    val normalizedQuestion = normalizeForRuleMatching(question)
    val questionYear = extractFirstNumberBeforeWord(normalizedQuestion, "yil")
    val questionAge = extractFirstNumberBeforeWord(normalizedQuestion, "yas")

    val leaveAnswer = questionYear?.let { year ->
        inferAnnualLeaveDays(year, chunks)
    }
    if (leaveAnswer != null) return leaveAnswer

    val ageAnswer = questionAge?.let { age ->
        inferAgeBasedLeaveDays(age, chunks)
    }
    if (ageAnswer != null) return ageAnswer

    if (!isRuleLikeQuestion(normalizedQuestion)) {
        return null
    }

    return inferExplicitRuleAnswer(normalizedQuestion, chunks)
}

fun sanitizeRagOutput(rawText: String): String {
    if (rawText.isBlank()) return rawText
    val withoutThinkBlocks = rawText
        .replace(Regex("""(?is)<think>.*?</think>"""), " ")
        .replace(Regex("""(?i)</?think>"""), " ")
    val withoutTemperatureHints = withoutThinkBlocks.replace(
        Regex("""(?m)\[temperature\s*=\s*[^]]+\]\s*"""),
        ""
    )
    val withoutLabels = withoutTemperatureHints
        .replace(Regex("""(?im)^\s*(Cevap|Yanıt|Answer)\s*:\s*"""), "")
        .replace(Regex("""(?im)^\s*[?]+\s*$"""), "")
        .replace(Regex("""(?im)^\s*(Bu bilgi|Belge bağlamından|Kaynaklar?)\b.*$"""), "")
    return withoutLabels
        .replace(Regex("""\s+"""), " ")
        .trim()
}

fun createDocumentId(): String = UUID.randomUUID().toString()

fun truncateForPreview(text: String, maxLength: Int = 280): String {
    val trimmed = text.trim()
    if (trimmed.length <= maxLength) return trimmed
    return trimmed.take(maxLength).trimEnd() + "..."
}

fun cosineSimilarity(left: FloatArray, right: FloatArray): Double {
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    for (i in left.indices) {
        val lv = left[i].toDouble()
        val rv = right[i].toDouble()
        dot += lv * rv
        leftNorm += lv * lv
        rightNorm += rv * rv
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
    return dot / (sqrt(leftNorm) * sqrt(rightNorm))
}

fun floatArrayToBytes(vector: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    vector.forEach { buffer.putFloat(it) }
    return buffer.array()
}

private data class RuleMatch(
    val days: Int,
    val sourceChunk: RagChunk,
    val reason: String
)

private fun inferAnnualLeaveDays(year: Int, chunks: List<RagChunk>): StrictRagAnswer? {
    val rules = mutableListOf<RuleMatch>()
    chunks.forEach { chunk ->
        chunk.text.lineSequence().forEach { line ->
            val normalizedLine = normalizeForRuleMatching(line)
            val rule = parseAnnualLeaveRule(year, normalizedLine, chunk)
            if (rule != null) {
                rules += rule
            }
        }
    }

    val preferred = rules.firstOrNull() ?: return null
    return StrictRagAnswer(
        answer = "${year} yıl çalışan için yıllık izin hakkı ${preferred.days} gündür.",
        sourceChunks = listOf(preferred.sourceChunk)
    )
}

private fun inferAgeBasedLeaveDays(age: Int, chunks: List<RagChunk>): StrictRagAnswer? {
    chunks.forEach { chunk ->
        chunk.text.lineSequence().forEach { line ->
            val normalizedLine = normalizeForRuleMatching(line)
            val rulePattern = Regex("""(\d+)\s*yas\s*ustu.*?(\d+)\s*gun""")
            val match = rulePattern.find(normalizedLine) ?: return@forEach
            val threshold = match.groupValues[1].toInt()
            val days = match.groupValues[2].toInt()
            if (age > threshold) {
                return StrictRagAnswer(
                    answer = "${age} yaş üstü çalışan için yıllık izin hakkı ${days} gündür.",
                    sourceChunks = listOf(chunk)
                )
            }
        }
    }
    return null
}

private fun inferExplicitRuleAnswer(normalizedQuestion: String, chunks: List<RagChunk>): StrictRagAnswer? {
    val questionTokens = normalizedQuestion
        .split(' ')
        .filter { it.isNotBlank() && it !in stopWords }
        .toSet()

    val bestChunk = chunks
        .map { chunk ->
            val chunkTokens = normalizeForRuleMatching(chunk.text)
                .split(' ')
                .filter { it.isNotBlank() && it !in stopWords }
                .toSet()
            val overlap = questionTokens.intersect(chunkTokens).size
            chunk to overlap
        }
        .maxByOrNull { it.second }
        ?.takeIf { it.second > 0 }
        ?.first
        ?: return null

    return if (bestChunk.text.isNotBlank()) {
        StrictRagAnswer(
            answer = truncateForPreview(bestChunk.text, maxLength = 240),
            sourceChunks = listOf(bestChunk),
            answerMode = "Kisa alinti"
        )
    } else {
        null
    }
}

private fun isRuleLikeQuestion(normalizedQuestion: String): Boolean {
    val keywords = listOf(
        "izin",
        "yillik",
        "yas",
        "kural",
        "hak",
        "sure",
        "saat",
        "gun",
        "calisma",
        "rapor",
        "süt",
        "sut",
        "mazeret",
        "dogum"
    )
    return keywords.any { normalizedQuestion.contains(it) }
}

private fun normalizeForRuleMatching(text: String): String {
    return text.lowercase()
        .replace("ı", "i")
        .replace("İ", "i")
        .replace("ş", "s")
        .replace("ğ", "g")
        .replace("ü", "u")
        .replace("ö", "o")
        .replace("ç", "c")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun extractFirstNumberBeforeWord(text: String, word: String): Int? {
    val regex = Regex("""(\d+)\s+$word\b""")
    return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun parseAnnualLeaveRule(year: Int, line: String, chunk: RagChunk): RuleMatch? {
    val patterns = listOf(
        Regex("""(\d+)\s*yildan\s+(\d+)\s*yila\s+kadar.*?(\d+)\s*gun"""),
        Regex("""(\d+)\s*yildan\s+(\d+)\s*yil\s+arasinda.*?(\d+)\s*gun"""),
        Regex("""(\d+)\s*yildan\s+fazla\s+(\d+)\s*yildan\s+az.*?(\d+)\s*gun"""),
        Regex("""(\d+)\s*yil\s*\(dahil\).*?(\d+)\s*gun"""),
        Regex("""(\d+)\s*yil\s+ustu.*?(\d+)\s*gun""")
    )

    patterns.forEach { pattern ->
        val match = pattern.find(line) ?: return@forEach
        val first = match.groupValues[1].toInt()
        val second = match.groupValues[2].toIntOrNull()
        val days = match.groupValues.last().toInt()
        val matches = when (pattern.pattern) {
            patterns[0].pattern, patterns[1].pattern -> year >= first && second != null && year <= second
            patterns[2].pattern -> year > first && second != null && year < second
            patterns[3].pattern -> year <= first
            else -> year > first
        }
        if (matches) {
            return RuleMatch(days, chunk, line)
        }
    }
    return null
}
