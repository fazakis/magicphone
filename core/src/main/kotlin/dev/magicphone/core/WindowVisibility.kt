// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

/** Geometry only: never reads or authorizes the contents of an occluding window. */
object WindowVisibility {
    fun intersection(a: Rect, b: Rect): Rect? = Rect(maxOf(a.left, b.left), maxOf(a.top, b.top),
        minOf(a.right, b.right), minOf(a.bottom, b.bottom)).takeIf { it.left < it.right && it.top < it.bottom }

    fun exposed(target: Rect, covers: List<Rect>): List<Rect> {
        if (target.left >= target.right || target.top >= target.bottom || covers.size > 128) return emptyList()
        var visible = listOf(target)
        for (cover in covers) {
            visible = visible.flatMap { area ->
                val cut = intersection(area, cover)
                if (cut == null) listOf(area) else listOf(
                    Rect(area.left, area.top, area.right, cut.top),
                    Rect(area.left, cut.bottom, area.right, area.bottom),
                    Rect(area.left, cut.top, cut.left, cut.bottom),
                    Rect(cut.right, cut.top, area.right, cut.bottom),
                ).filter { it.left < it.right && it.top < it.bottom }
            }
            if (visible.size > 512) return emptyList()
        }
        return visible
    }
}
