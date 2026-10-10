// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlinx.serialization.Serializable

/** Transient presentation data. Coordinates are thousandths of the captured app image. */
@Serializable
data class ExplanationRegion(val left: Int, val top: Int, val right: Int, val bottom: Int,
    val style: String = "rectangle") {
    fun validate() {
        require(left in 0..999 && top in 0..999 && right in 1..1000 && bottom in 1..1000)
        require(left < right && top < bottom && style in setOf("rectangle", "pointer", "underline", "ellipse", "highlight"))
    }
    fun onScreen(capture: Rect): Rect = Rect(
        capture.left + (capture.right - capture.left) * left / 1000,
        capture.top + (capture.bottom - capture.top) * top / 1000,
        capture.left + (capture.right - capture.left) * right / 1000,
        capture.top + (capture.bottom - capture.top) * bottom / 1000,
    )
}

@Serializable
data class ExplanationSection(val text: String, val regions: List<ExplanationRegion> = emptyList())

@Serializable
data class Explanation(val sections: List<ExplanationSection>) {
    fun validate() {
        require(sections.size in 1..24 && transcript.length <= 8000)
        sections.forEach {
            require(it.text.isNotBlank() && it.text.length <= 1200 && it.regions.size <= 8)
            it.regions.forEach(ExplanationRegion::validate)
        }
    }
    val transcript get() = sections.joinToString("\n\n") { it.text }
}
