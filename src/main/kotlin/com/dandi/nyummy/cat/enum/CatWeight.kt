package com.dandi.nyummy.cat.enum

enum class CatWeight(val weight: Int, val description: String) {
    LEAN(-2, "홀쭉냥"),
    SLIM(-1, "날씬냥"),
    NORMAL(0, "보통냥"),
    CHUBBY(1, "통통냥"),
    PLUMP(2, "뚱냥이"),
    ;

    val slug: String get() = name.lowercase()

    companion object {
        private val weightMap = entries.associateBy { it.weight }

        val MIN_WEIGHT: Int = entries.minOf { it.weight }
        val MAX_WEIGHT: Int = entries.maxOf { it.weight }

        fun fromWeight(weight: Int): CatWeight =
            weightMap[weight] ?: throw IllegalStateException("허용 범위를 벗어난 고양이 체형 값입니다: $weight")
    }
}
