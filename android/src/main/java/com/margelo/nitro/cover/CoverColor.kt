package com.margelo.nitro.cover

import android.graphics.Color

/**
 * Accepts `#RGB`, `#RGBA`, `#RRGGBB`, and `#RRGGBBAA`. The 4- and 8-char
 * forms are RGB-then-alpha (CSS convention), matching the iOS
 * implementation. Names like `"red"` and the AARRGGBB ordering are
 * intentionally rejected so iOS and Android behave identically.
 *
 * Returns null for unparsable input — callers fall back to opaque black.
 */
internal fun parseCoverHex(hex: String): Int? {
  var s = hex.trim()
  if (s.startsWith("#")) s = s.substring(1)
  val expanded = when (s.length) {
    3 -> buildString(6) { for (c in s) { append(c); append(c) } }
    4 -> buildString(8) { for (c in s) { append(c); append(c) } }
    6, 8 -> s
    else -> return null
  }
  return try {
    val rgba = expanded.toLong(16)
    when (expanded.length) {
      6 -> 0xFF000000.toInt() or rgba.toInt()
      8 -> {
        val r = ((rgba shr 24) and 0xFF).toInt()
        val g = ((rgba shr 16) and 0xFF).toInt()
        val b = ((rgba shr 8) and 0xFF).toInt()
        val a = (rgba and 0xFF).toInt()
        Color.argb(a, r, g, b)
      }
      else -> null
    }
  } catch (_: NumberFormatException) {
    null
  }
}
