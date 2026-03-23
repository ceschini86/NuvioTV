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

    private val threeLetterMap = mapOf(
        "eng" to "English", "spa" to "Spanish", "fre" to "French",
        "ger" to "German", "ita" to "Italian", "por" to "Portuguese",
        "rus" to "Russian", "jpn" to "Japanese", "kor" to "Korean",
        "chi" to "Chinese", "ara" to "Arabic", "hin" to "Hindi",
        "tur" to "Turkish", "pol" to "Polish", "dut" to "Dutch",
        "swe" to "Swedish", "dan" to "Danish", "nor" to "Norwegian",
        "fin" to "Finnish", "ces" to "Czech", "hun" to "Hungarian",
        "ron" to "Romanian", "ell" to "Greek", "tha" to "Thai",
        "vie" to "Vietnamese", "ind" to "Indonesian", "msa" to "Malay",
        "heb" to "Hebrew", "ukr" to "Ukrainian", "bul" to "Bulgarian",
        "hrv" to "Croatian", "slk" to "Slovak", "slv" to "Slovenian"
    )

    fun fromTwoLettersToLanguage(code: String): String? = languageMap[code.lowercase()]

    fun fromThreeLettersToLanguage(code: String): String? = threeLetterMap[code.lowercase()]

    fun fromLanguageToTwoLetters(language: String): String? =
        languageMap.entries.firstOrNull { it.value.equals(language, ignoreCase = true) }?.key
}
