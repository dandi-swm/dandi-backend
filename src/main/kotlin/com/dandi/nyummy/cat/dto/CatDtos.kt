package com.dandi.nyummy.cat.dto

data class CatResponse(val id: Long, val weight: Int, val weightDescription: String)

data class CatAnimationResponse(val weight: String, val baseUrl: String, val animations: List<CatAnimation>)

data class CatAnimation(
    val state: String,
    val moment: String,
    val frame: Frame,
    val animation: List<List<AnimationSource>>,
    val text: List<String>,
)

data class Frame(val width: Int, val height: Int, val framesPerRow: Int, val durationMs: Int)

data class AnimationSource(val src: String, val frames: Int, val loop: Boolean = false)
