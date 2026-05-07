package com.margelo.nitro.cover

import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView

internal fun CoverResizeMode.toScaleType(): ImageView.ScaleType = when (this) {
  CoverResizeMode.COVER -> ImageView.ScaleType.CENTER_CROP
  CoverResizeMode.CONTAIN -> ImageView.ScaleType.FIT_CENTER
  CoverResizeMode.STRETCH -> ImageView.ScaleType.FIT_XY
  CoverResizeMode.CENTER -> ImageView.ScaleType.CENTER
}

internal fun CoverEasing?.toInterpolator(): Interpolator =
  when (this ?: CoverEasing.EASEINOUT) {
    CoverEasing.LINEAR -> LinearInterpolator()
    CoverEasing.EASEIN -> AccelerateInterpolator()
    CoverEasing.EASEOUT -> DecelerateInterpolator()
    CoverEasing.EASEINOUT -> AccelerateDecelerateInterpolator()
  }

internal fun CoverBlurStyle.fallbackColor(): Int = when (this) {
  CoverBlurStyle.LIGHT -> 0xCCFFFFFF.toInt()
  CoverBlurStyle.EXTRALIGHT -> 0xE6FFFFFF.toInt()
  CoverBlurStyle.REGULAR -> 0x99888888.toInt()
  CoverBlurStyle.DARK -> 0xCC000000.toInt()
}

internal fun CoverBlurStyle.tintColor(): Int = when (this) {
  CoverBlurStyle.LIGHT -> 0x40FFFFFF
  CoverBlurStyle.EXTRALIGHT -> 0x80FFFFFF.toInt()
  CoverBlurStyle.REGULAR -> Color.TRANSPARENT
  CoverBlurStyle.DARK -> 0x66000000
}
