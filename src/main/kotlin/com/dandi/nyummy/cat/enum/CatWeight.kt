package com.dandi.nyummy.cat.enum

/** 고양이 체형. 화면에 쓰는 이름은 서버 고정 문구 파일의 `catWeight.<이름>`에 있다. */
enum class CatWeight(val weight: Int) {
    LEAN(-2),
    SLIM(-1),
    NORMAL(0),
    CHUBBY(1),
    PLUMP(2),
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
