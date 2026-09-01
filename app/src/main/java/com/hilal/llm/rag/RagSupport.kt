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

private const val VECTOR_DIMENSION = 384

internal object RagVectorEncoder {
    fun encode(text: String): FloatArray {
        val vector = FloatArray(VECTOR_DIMENSION)
        val normalized = normalizeText(text)
        if (normalized.isBlank()) return vector

        val tokens = tokenizerRegex
            .split(normalized)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in stopWords }
            .toList()

        tokens.forEachIndexed { index, token ->
            val hash = token.hashCode()
            val primaryBucket = Math.floorMod(hash, VECTOR_DIMENSION)
            val secondaryBucket = Math.floorMod(hash * 31 + token.length, VECTOR_DIMENSION)
            val weight = 1f + minOf(token.length, 12) / 12f
            vector[primaryBucket] += weight
            vector[secondaryBucket] += weight * 0.5f

            if (index > 0) {
                val bigram = tokens[index - 1] + "_" + token
                val bigramBucket = Math.floorMod(bigram.hashCode(), VECTOR_DIMENSION)
                vector[bigramBucket] += 0.35f
            }
        }

        normalizeVector(vector)
        return vector
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace("ı", "i")
            .replace("İ", "i")
            .replace("ş", "s")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ö", "o")
            .replace("ç", "c")
    }

    private fun normalizeVector(vector: FloatArray) {
        var sumSquares = 0.0
        for (value in vector) {
            sumSquares += value.toDouble() * value.toDouble()
        }
        if (sumSquares <= 0.0) return

        val magnitude = kotlin.math.sqrt(sumSquares).toFloat()
        if (magnitude == 0f) return

        for (i in vector.indices) {
            vector[i] /= magnitude
        }
    }
}

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

fun buildChunksFromDocument(
    documentId: String,
    displayName: String,
    text: String,
    chunkSize: Int = 900,
    overlap: Int = 150
): List<RagChunk> {
    val chunks = chunkText(text, chunkSize = chunkSize, overlap = overlap)
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
    limit: Int = 4
): List<RagChunk> {
    if (chunks.isEmpty()) return emptyList()

    val questionVector = RagVectorEncoder.encode(question)
    return chunks
        .map { chunk ->
            val chunkVector = RagVectorEncoder.encode(chunk.text)
            val score = cosineSimilarity(questionVector, chunkVector)
            chunk to score
        }
        .sortedWith(compareByDescending<Pair<RagChunk, Double>> { it.second }
            .thenBy { it.first.documentName }
            .thenBy { it.first.chunkIndex })
        .map { it.first }
        .take(limit)
}

fun rankChunksWithEmbeddings(
    questionEmbedding: FloatArray,
    chunks: List<RagChunk>,
    chunkEmbeddings: Map<String, FloatArray>,
    limit: Int = 4
): List<RagChunk> {
    if (chunks.isEmpty()) return emptyList()

    return chunks
        .map { chunk ->
            val chunkVector = chunkEmbeddings[chunkEmbeddingKey(chunk)]
                ?: throw IllegalStateException("Missing embedding for ${chunk.documentName} / parça ${chunk.chunkIndex + 1}")
            val score = cosineSimilarity(questionEmbedding, chunkVector)
            chunk to score
        }
        .sortedWith(compareByDescending<Pair<RagChunk, Double>> { it.second }
            .thenBy { it.first.documentName }
            .thenBy { it.first.chunkIndex })
        .map { it.first }
        .take(limit)
}

fun rankChunksByKeywordOverlap(
    question: String,
    chunks: List<RagChunk>,
    limit: Int = 4
): List<RagChunk> {
    if (chunks.isEmpty()) return emptyList()

    val questionTokens = tokenizeForRanking(question)
    if (questionTokens.isEmpty()) {
        return chunks
            .sortedWith(compareBy<RagChunk> { it.documentName }.thenBy { it.chunkIndex })
            .take(limit)
    }

    return chunks
        .map { chunk ->
            val chunkTokens = tokenizeForRanking(chunk.text)
            val questionEntities = RagGraphRetriever.extractEntities(question)
            val chunkEntities = RagGraphRetriever.extractEntities(chunk.text)
            val overlapScore = chunkTokens.count { it in questionTokens }
            val exactPhraseBonus = if (chunk.text.contains(question.trim(), ignoreCase = true)) 3 else 0
            val entityScore = chunkEntities.count { it in questionEntities } * 4
            val phraseScore = chunkEntities.count { it.contains('_') && it in questionEntities } * 8
            chunk to (overlapScore + exactPhraseBonus + entityScore + phraseScore)
        }
        .sortedWith(
            compareByDescending<Pair<RagChunk, Int>> { it.second }
                .thenBy { it.first.documentName }
                .thenBy { it.first.chunkIndex }
        )
        .map { it.first }
        .take(limit)
}

fun buildRagPrompt(question: String, chunks: List<RagChunk>): String {
    val criticalNumbers = Regex("""\d+""")
        .findAll(question)
        .map { it.value }
        .toList()
    val criticalTerms = listOf("yıl", "izin", "gün")
        .filter { question.contains(it, ignoreCase = true) }

    val context = if (chunks.isEmpty()) {
        "Belge bağlamı bulunamadı."
    } else {
        val selectedChunkContext = chunks.joinToString(separator = "\n\n") { chunk ->
            buildString {
                append('[')
                append(chunk.documentName)
                append(" / Parça ")
                append(chunk.chunkIndex + 1)
                append("]\n")
                append(chunk.text.trim())
            }
        }
        val relevantEvidence = buildRelevantEvidence(question, chunks)
        if (relevantEvidence.isBlank()) {
            selectedChunkContext
        } else {
            "$relevantEvidence\n\nSeçilen kaynak parçalar:\n$selectedChunkContext"
        }
    }

    return """
        Sen bir belge soru-cevap asistanısın.
        Cevabı yalnızca aşağıdaki belge bağlamını kullanarak ver.
        Cevabın tamamını Türkçe yaz.
        İngilizce kelimeler, cümleler, başlıklar veya etiketler kullanma.
        Bilgi belgelerde yoksa bunu açıkça söyle.
        Belgedeki ifadeyi uydurma, tahmin etme veya genelleme yapma.
        Eğer soruda bir sayı varsa, belgedeki sayı aralıklarını bu sayıya göre eşleştir.
        Aynı bağlamda birden fazla kural varsa, yalnızca sorudaki sayıyı kapsayan kuralı kullan.
        Cevap verirken belgedeki ilgili satırı esas al ve komşu satırlardaki farklı aralıkları karıştırma.
        Cevap kısa ve doğrudan olsun.
        Başlık, madde işareti, numaralandırma, "Cevap:" etiketi, düşünme metni ve kaynak notu yazma.

        Soru içindeki kritik sayı(lar): ${criticalNumbers.joinToString(", ").ifBlank { "yok" }}
        Soru içindeki kritik terimler: ${criticalTerms.joinToString(", ").ifBlank { "yok" }}

        Belge bağlamı:
        $context

        Soru:
        ${question.trim()}
    """.trimIndent()
}

private fun buildRelevantEvidence(question: String, chunks: List<RagChunk>): String {
    val questionTokens = tokenizeForRanking(question)
    val questionNumbers = Regex("""\d+""")
        .findAll(question)
        .map { it.value }
        .toSet()
    val questionYear = extractFirstQuestionYear(question)

    return chunks.flatMap { chunk ->
        val sentences = splitIntoEvidenceSentences(chunk.text)
        sentences.map { sentence ->
            val score = scoreEvidenceSentence(
                question = question,
                sentence = sentence,
                questionTokens = questionTokens,
                questionNumbers = questionNumbers,
                questionYear = questionYear
            )
            chunk to (sentence.trim() to score)
        }
    }
        .sortedWith(
            compareByDescending<Pair<RagChunk, Pair<String, Int>>> { it.second.second }
                .thenBy { it.first.documentName }
                .thenBy { it.first.chunkIndex }
        )
        .take(6)
        .joinToString(separator = "\n\n") { (chunk, sentenceScore) ->
            val (sentence, score) = sentenceScore
            buildString {
                append('[')
                append(chunk.documentName)
                append(" / Parça ")
                append(chunk.chunkIndex + 1)
                append(" | Skor ")
                append(score)
                append("]\n")
                append(sentence)
            }
        }
}

private fun scoreEvidenceSentence(
    question: String,
    sentence: String,
    questionTokens: Set<String>,
    questionNumbers: Set<String>,
    questionYear: Int?
): Int {
    val sentenceTokens = tokenizeForRanking(sentence)
    val overlap = sentenceTokens.count { it in questionTokens }
    val exactQuestionPhraseBonus = if (sentence.contains(question.trim(), ignoreCase = true)) 8 else 0
    val numberBonus = if (questionNumbers.any { it in sentence }) 4 else 0
    val yearBonus = if (question.contains("yıl", ignoreCase = true) && sentence.contains("yıl", ignoreCase = true)) 3 else 0
    val leaveBonus = if (question.contains("izin", ignoreCase = true) && sentence.contains("izin", ignoreCase = true)) 3 else 0
    val yearRangeBonus = questionYear?.let { scoreSentenceYearRange(it, sentence) } ?: 0
    return overlap + exactQuestionPhraseBonus + numberBonus + yearBonus + leaveBonus + yearRangeBonus
}

private fun extractFirstQuestionYear(question: String): Int? {
    return Regex("""\d+""")
        .findAll(question)
        .firstOrNull()
        ?.value
        ?.toIntOrNull()
}

private fun scoreSentenceYearRange(questionYear: Int, sentence: String): Int {
    val yearNumbers = Regex("""(\d+)\s*yıl""", RegexOption.IGNORE_CASE)
        .findAll(sentence)
        .mapNotNull { matchResult -> matchResult.groupValues.getOrNull(1)?.toIntOrNull() }
        .toList()

    if (yearNumbers.size < 2) return 0

    var bestScore = 0
    for (index in 0 until yearNumbers.lastIndex) {
        val lower = minOf(yearNumbers[index], yearNumbers[index + 1])
        val upper = maxOf(yearNumbers[index], yearNumbers[index + 1])
        if (questionYear in lower..upper) {
            bestScore = maxOf(bestScore, 12)
        } else {
            val distance = minOf(
                kotlin.math.abs(questionYear - lower),
                kotlin.math.abs(questionYear - upper)
            )
            bestScore = maxOf(bestScore, (6 - distance).coerceAtLeast(0))
        }
    }
    return bestScore
}

private fun splitIntoEvidenceSentences(text: String): List<String> {
    return text
        .split(Regex("""(?<=[.!?\n])\s+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

data class StrictRagAnswer(
    val answer: String,
    val sourceChunks: List<RagChunk>,
    val answerMode: String = "Kurallı çıkarım"
)

fun buildStrictRagAnswer(question: String, chunks: List<RagChunk>): StrictRagAnswer? {
    val normalizedQuestion = normalizeForRuleMatching(question)
    val questionYear = extractServiceYears(normalizedQuestion)
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
        .replace(Regex("""(?is)\s*Kaynak\s*(notu)?\s*:.*$"""), "")
    return withoutLabels
        .replace(Regex("""(?<=[\p{L}])(?=\d)"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

fun createDocumentId(): String = UUID.randomUUID().toString()

fun chunkEmbeddingKey(chunk: RagChunk): String {
    return "${chunk.documentId}:${chunk.chunkIndex}"
}

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

private fun tokenizeForRanking(text: String): Set<String> {
    return tokenizerRegex
        .split(text.lowercase())
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && it !in stopWords }
        .toSet()
}

private fun extractServiceYears(text: String): Int? {
    val patterns = listOf(
        Regex("""(\d+)\s*yil\b"""),
        Regex("""(\d+)\s*yillik\b"""),
        Regex("""(\d+)\s*senelik\b""")
    )

    for (pattern in patterns) {
        val match = pattern.find(text)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()
        }
    }
    return null
}
