package com.hilal.llm.benchmark

enum class ResponseQuality {
    UNKNOWN,
    GOOD,
    PARTIAL,
    BAD,
    REPEATED,
    EMPTY
}

fun inferResponseQuality(response: String): ResponseQuality {
    val trimmed = response.trim()
    if (trimmed.isEmpty()) return ResponseQuality.EMPTY

    val normalizedSentences = trimmed
        .split(Regex("[.!?]\\s+|\\n+"))
        .map { it.trim().lowercase().trimEnd('.', '!', '?', ',', ';', ':') }
        .filter { it.isNotBlank() }

    if (normalizedSentences.isNotEmpty()) {
        val uniqueCount = normalizedSentences.toSet().size
        if (normalizedSentences.size >= 2 && uniqueCount < normalizedSentences.size) {
            return ResponseQuality.REPEATED
        }
    }

    val repeatedPhrase = Regex("\\b(\\w{3,})\\b(?:\\s+\\1\\b){2,}", RegexOption.IGNORE_CASE)
    if (repeatedPhrase.containsMatchIn(trimmed)) {
        return ResponseQuality.REPEATED
    }

    return ResponseQuality.UNKNOWN
}

fun ResponseQuality.displayLabel(): String {
    return when (this) {
        ResponseQuality.UNKNOWN -> "Not rated"
        ResponseQuality.GOOD -> "Good"
        ResponseQuality.PARTIAL -> "Partial"
        ResponseQuality.BAD -> "Bad"
        ResponseQuality.REPEATED -> "Repeated"
        ResponseQuality.EMPTY -> "Empty"
    }
}
