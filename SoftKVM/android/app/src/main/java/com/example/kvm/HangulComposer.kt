package com.example.kvm

/** 두벌식 한글 조합기. input()은 완성되어 확정할 글자를 돌려주고, current()는 조합 중인 글자. */
class HangulComposer {
    private var cho: Char? = null
    private var jung: Char? = null
    private var jong: Char? = null

    companion object {
        private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
        private const val KEYS = "qwertyuiopasdfghjklzxcvbnm"
        private const val JAMO = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"
        private val SHIFTED = mapOf('q' to 'ㅃ', 'w' to 'ㅉ', 'e' to 'ㄸ', 'r' to 'ㄲ', 't' to 'ㅆ', 'o' to 'ㅒ', 'p' to 'ㅖ')

        private val VOWEL_COMB = mapOf(
            "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ',
            "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ',
        )
        private val JONG_COMB = mapOf(
            "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ',
            "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ',
        )
        private val VOWEL_SPLIT = VOWEL_COMB.entries.associate { it.value to it.key }
        private val JONG_SPLIT = JONG_COMB.entries.associate { it.value to it.key }

        fun jamoFor(lower: Char, shift: Boolean): Char? {
            if (shift) SHIFTED[lower]?.let { return it }
            val i = KEYS.indexOf(lower)
            return if (i >= 0) JAMO[i] else null
        }

        private fun isVowel(c: Char) = JUNG.indexOf(c) >= 0
    }

    fun isEmpty() = cho == null && jung == null && jong == null

    fun reset() { cho = null; jung = null; jong = null }

    fun current(): String {
        val c = cho
        val v = jung
        if (c != null && v != null) {
            val j = jong?.let { JONG.indexOf(it) } ?: 0
            return (0xAC00 + (CHO.indexOf(c) * 21 + JUNG.indexOf(v)) * 28 + j).toChar().toString()
        }
        return (c ?: v)?.toString() ?: ""
    }

    fun input(j: Char): String {
        if (isVowel(j)) {
            val jg = jong
            if (jg != null) {                       // 받침이 다음 글자 초성으로 넘어감 (닭+ㅏ → 달가)
                val split = JONG_SPLIT[jg]
                val moved: Char
                if (split != null) { jong = split[0]; moved = split[1] } else { jong = null; moved = jg }
                val done = current()
                reset(); cho = moved; jung = j
                return done
            }
            if (cho != null && jung == null) { jung = j; return "" }
            jung?.let { v -> VOWEL_COMB["$v$j"]?.let { jung = it; return "" } }
            val done = current()
            reset(); jung = j
            return done
        } else {
            if (isEmpty()) { cho = j; return "" }
            if (cho != null && jung != null) {
                val jg = jong
                if (jg == null) {
                    if (JONG.indexOf(j) > 0) { jong = j; return "" }
                } else {
                    JONG_COMB["$jg$j"]?.let { jong = it; return "" }
                }
            }
            val done = current()
            reset(); cho = j
            return done
        }
    }

    /** 조합 중인 글자를 한 단계 지움. 지울 게 없으면 false */
    fun backspace(): Boolean {
        jong?.let { jong = JONG_SPLIT[it]?.get(0); return true }
        jung?.let { jung = VOWEL_SPLIT[it]?.get(0); return true }
        if (cho != null) { cho = null; return true }
        return false
    }
}
