package com.lagradost.cloudstream3.utils

object SubtitleHelper {
    private val languageMap = mapOf(
        "en" to "English", "es" to "Spanish", "fr" to "French",
        "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "ru" to "Russian", "ja" to "Japanese", "ko" to "Korean",
        "zh" to "Chinese", "ar" to "Arabic", "hi" to "Hindi",
        "tr" to "Turkish", "pl" to "Polish", "nl" to "Dutch",
        "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian",
        "fi" to "Finnish", "cs" to "Czech", "hu" to "Hungarian",
        "ro" to "Romanian", "el" to "Greek", "th" to "Thai",
        "vi" to "Vietnamese", "id" to "Indonesian", "ms" to "Malay",
        "he" to "Hebrew", "uk" to "Ukrainian", "bg" to "Bulgarian",
        "hr" to "Croatian", "sk" to "Slovak", "sl" to "Slovenian"
    )

    fun fromTwoLettersToLanguage(code: String): String? = languageMap[code.lowercase()]

    fun fromLanguageToTwoLetters(language: String): String? =
        languageMap.entries.firstOrNull { it.value.equals(language, ignoreCase = true) }?.key
}
