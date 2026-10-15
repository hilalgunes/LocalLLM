package com.hilal.llm.rag

import java.util.Locale
import kotlin.math.ln

/** Relevance labels are 1-based passage numbers supplied by the test author. */
data class ComparisonQuery(val question: String, val relevant: Set<Int>, val reference: String)
data class ComparisonDataset(val passages: List<String>, val queries: List<ComparisonQuery>)
data class RetrievalScores(val precision: Double, val recall: Double, val reciprocalRank: Double, val ndcg: Double, val hit: Boolean)

internal fun parseComparisonDataset(passages: String, queries: String, maxPassages: Int = 100, maxQueries: Int = 30): ComparisonDataset {
    val docs = passages.lines().map(String::trim).filter(String::isNotBlank)
    require(docs.size in 2..maxPassages) { "2–$maxPassages pasaj girin; her satır ayrı bir kaynaktır." }
    require(docs.all { it.length <= 3000 }) { "Bir pasaj en fazla 3000 karakter olabilir." }
    val cases = queries.lines().filter(String::isNotBlank).mapIndexed { index, line ->
        val parts = line.split('|', limit = 3).map(String::trim)
        require(parts.size == 3 && parts[0].isNotBlank()) { "Sorgu ${index + 1}: soru | kaynak numaraları | referans yanıt" }
        val ids = parts[1].split(',').map { it.trim().toIntOrNull() ?: error("Sorgu ${index + 1}: kaynak numarası geçersiz.") }.toSet()
        require(ids.isNotEmpty() && ids.all { it in 1..docs.size }) { "Sorgu ${index + 1}: kaynaklar 1–${docs.size} arasında olmalı." }
        ComparisonQuery(parts[0], ids, parts[2])
    }
    require(cases.size in 1..maxQueries) { "1–$maxQueries sorgu girin." }
    return ComparisonDataset(docs, cases)
}

internal fun retrievalScores(ranking: List<Int>, relevant: Set<Int>, k: Int): RetrievalScores {
    require(relevant.isNotEmpty() && k > 0)
    val selected = ranking.take(k)
    val hits = selected.count { it in relevant }
    val first = ranking.indexOfFirst { it in relevant }
    val dcg = selected.mapIndexed { index, id -> if (id in relevant) 1.0 / (ln(index + 2.0) / ln(2.0)) else 0.0 }.sum()
    val ideal = (0 until minOf(k, relevant.size)).sumOf { 1.0 / (ln(it + 2.0) / ln(2.0)) }
    return RetrievalScores(hits.toDouble() / selected.size.coerceAtLeast(1), hits.toDouble() / relevant.size,
        if (first < 0) 0.0 else 1.0 / (first + 1), dcg / ideal, hits > 0)
}

/** Lexical overlap with a reference, deliberately not called factual correctness. */
internal fun referenceTokenF1(answer: String, reference: String): Double? {
    if (reference.isBlank()) return null
    fun tokens(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(Locale.forLanguageTag("tr"))).map { it.value }.toList()
    val actual = tokens(answer); val expected = tokens(reference)
    if (actual.isEmpty() || expected.isEmpty()) return 0.0
    val counts = expected.groupingBy { it }.eachCount().toMutableMap()
    var matched = 0
    for (token in actual) if ((counts[token] ?: 0) > 0) { matched++; counts[token] = counts.getValue(token) - 1 }
    return 2.0 * matched / (actual.size + expected.size)
}

internal val defaultComparisonPassages = """
Çalışanların yıllık izin başvurusu en az yedi gün önceden yöneticisine iletilmelidir.
Bir ile beş yıl arasında kıdemi olan çalışanlar yılda en az on dört gün ücretli izin kullanabilir.
Ürünler satın alma tarihinden itibaren on dört gün içinde faturasıyla iade edilebilir.
Standart teslimat üç iş günü sürer. Ekspres kargo ertesi iş günü teslim edilir.
Dizüstü bilgisayarın garanti süresi iki yıldır. Sıvı hasarı garanti kapsamına girmez.
Kütüphane hafta içi sabah dokuzda açılır ve akşam altıda kapanır.
Ankara treninin kalkış saati on beş otuzdur. Yolcular yirmi dakika önce istasyonda olmalıdır.
Hesap şifresini yenilemek için giriş ekranındaki Şifremi unuttum bağlantısını kullanın.
""".trimIndent()
internal val defaultComparisonQueries = """
İzin talebimi ne kadar önceden bildirmeliyim? | 1 | En az yedi gün önceden.
Üç yıldır çalışıyorum, yıllık ücretli iznim en az kaç gün? | 2 | En az on dört gün.
Ürünü kaç gün içinde iade edebilirim? | 3 | On dört gün içinde, faturasıyla.
Hızlı kargo ne zaman teslim edilir? | 4 | Ertesi iş günü.
Bilgisayara su dökülürse garanti karşılar mı? | 5 | Hayır, sıvı hasarı garanti kapsamında değildir.
Kütüphane akşam kaçta kapanıyor? | 6 | Akşam altıda.
Ankara treni saat kaçta hareket ediyor? | 7 | On beş otuzda.
Parolamı hatırlamıyorum, ne yapmalıyım? | 8 | Giriş ekranındaki Şifremi unuttum bağlantısını kullanın.
""".trimIndent()
