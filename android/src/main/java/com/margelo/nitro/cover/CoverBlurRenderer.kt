package com.margelo.nitro.cover

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView

internal const val BLUR_VIEW_TAG = "CoverBlurView"

/// Maximum `RenderEffect` blur radius. Larger than this slows the GPU
/// pass without any further visual benefit because we capture at 1/4
/// scale (radius is in source pixels).
private const val BLUR_MAX_RADIUS = 50f

/// Capture scale for the GPU-blur path (API >= S). 1/4 in each
/// dimension cuts allocation 16× and the upscale on display is hidden
/// inside the `RenderEffect` blur.
private const val GPU_BLUR_CAPTURE_SCALE = 0.25f

/// Capture scale for the API < S downscale-upscale fallback. Much
/// smaller — 1/12 in each dimension (so 1/144 pixels) — because
/// there's no GPU blur to mask the upscaled blockiness. The bilinear
/// filter applied at draw time produces a smudge that approximates
/// the iOS UIBlurEffect look well enough to keep underlying app
/// content unreadable, even when the style tint is light.
private const val FALLBACK_BLUR_CAPTURE_SCALE = 0.083f

internal object CoverBlurRenderer {
  /// Capture the topmost host view (e.g. a Modal Dialog's decor when
  /// one is open) at 1/4 scale, blur it via `RenderEffect`, and apply
  /// to the target ImageView. Falls back to a flat tinted background
  /// on API < 31. No-op when the source view has no laid-out size.
  ///
  /// `alsoExclude` is the cover's WindowManager-attached root view —
  /// distinct from `target.rootView` on the SCVH path, where the
  /// SCVH-hosted FrameLayout (containing `target`) is NOT in
  /// `WindowManagerGlobal.mViews`, but the wrapping `SurfaceView`
  /// is. Without this exclude the blur source resolves to that
  /// SurfaceView, and software-drawing a SurfaceView produces a
  /// transparent bitmap (its content lives in a separate hardware
  /// surface) — leaving the blur cover translucent and the activity
  /// content visible through it.
  fun render(
    target: ImageView,
    activity: Activity,
    style: CoverBlurStyle,
    intensity: Float,
    alsoExclude: View? = null,
  ) {
    // Capturing the activity's decor alone would blur only what's
    // behind the modal, leaving the modal's content sharp under our
    // cover. Using the topmost host view fixes that. Falls back to the
    // activity decor when nothing else is in front.
    val source = CoverWindowAttachment.topmostHostViewFor(
      activity,
      exclude = target.rootView,
      exclude2 = alsoExclude,
    ) ?: activity.window?.decorView ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // API >= S: real GPU blur via RenderEffect. Capture at 1/4 — the
      // RenderEffect upscale hides any pixelation.
      val bitmap = captureViewBitmap(source, scale = GPU_BLUR_CAPTURE_SCALE) ?: return
      target.setImageBitmap(bitmap)
      applyRenderEffect(target, intensity)
      val tint = style.tintColor()
      target.foreground = if (tint != Color.TRANSPARENT) ColorDrawable(tint) else null
    } else {
      // API < S (Android 11 and below): RenderEffect doesn't exist.
      // The old fallback dropped the bitmap and painted a flat
      // semi-transparent tint, which left the underlying app fully
      // readable through ~20% transparency — broken privacy for any
      // host that runs on Android 11. Instead, capture VERY small
      // (1/144 pixels) and let ImageView's bilinear filter smudge it
      // back to full size during draw. That gives a frosted-glass
      // approximation good enough to obscure text while keeping the
      // colour palette of the underlying content (so the cover doesn't
      // jump abruptly from "app" to "flat block of colour"). Tint and
      // intensity then layer on top exactly like the API >= S path,
      // so the visual contract across versions is consistent.
      val bitmap = captureViewBitmap(source, scale = FALLBACK_BLUR_CAPTURE_SCALE)
      if (bitmap != null) {
        target.setImageBitmap(bitmap)
        val tint = style.tintColor()
        target.foreground = if (tint != Color.TRANSPARENT) ColorDrawable(tint) else null
        target.setBackgroundColor(Color.TRANSPARENT)
      } else {
        // Source view had no laid-out size (rare; e.g. first frame
        // before measure). Fall back to the original flat tint so the
        // privacy cover still hides content, just without the
        // smudged-content look.
        target.setImageDrawable(null)
        target.foreground = null
        target.setBackgroundColor(style.fallbackColor())
      }
    }
  }

  /// Update the blur radius on an already-rendered blur view in place.
  /// Returns `true` if it found a blur ImageView under `coverRoot` and
  /// updated it; `false` if the cover isn't currently in blur mode and
  /// the caller needs to do a full rebuild.
  ///
  /// `style` is needed so transitions to `intensity == 0` can also
  /// clear the style-tinted foreground — without this, dialing
  /// intensity from 0.5 to 0 left the tint painted as a flat wash,
  /// breaking the "0 = no blur" contract documented for both platforms.
  fun updateIntensity(coverRoot: View, style: CoverBlurStyle, intensity: Float): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val blurView = coverRoot.findViewWithTag<ImageView>(BLUR_VIEW_TAG) ?: return false
    applyRenderEffect(blurView, intensity)
    if (intensity <= 0f) {
      // No blur → no tint, so the cover reads as transparent / pass-
      // through-content. This matches the initial-render path's
      // `if (tint != Color.TRANSPARENT)` guard and the iOS
      // UIViewPropertyAnimator at fractionComplete=0 behavior.
      blurView.foreground = null
    } else {
      val tint = style.tintColor()
      blurView.foreground = if (tint != Color.TRANSPARENT) ColorDrawable(tint) else null
    }
    return true
  }

  private fun applyRenderEffect(target: ImageView, intensity: Float) {
    if (intensity <= 0f) {
      // Setting RenderEffect to null removes the blur entirely — keeps
      // parity with iOS where intensity 0 is no blur.
      target.setRenderEffect(null)
      return
    }
    val radius = (BLUR_MAX_RADIUS * intensity)
    target.setRenderEffect(
      RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
    )
  }

  private fun captureViewBitmap(view: View, scale: Float): Bitmap? {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return null
    return try {
      val w = (width * scale).toInt().coerceAtLeast(1)
      val h = (height * scale).toInt().coerceAtLeast(1)
      val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.scale(scale, scale)
      view.draw(canvas)
      bitmap
    } catch (_: Throwable) {
      null
    }
  }
}
