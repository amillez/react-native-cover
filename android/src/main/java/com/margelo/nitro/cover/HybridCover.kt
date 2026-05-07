package com.margelo.nitro.cover

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import java.lang.ref.WeakReference

private data class ImageConfig(
  val uri: String,
  val resize: CoverResizeMode,
  val x: CoverPositionX,
  val y: CoverPositionY,
  val width: Double,   // 0 = use full container width
  val height: Double,  // 0 = use full container height
  var bitmap: Bitmap? = null,
)

/// Default blur intensity when the JS caller passes `undefined` to
/// `setBlur(style)`. Mirrors the iOS default.
private const val DEFAULT_BLUR_INTENSITY: Float = 0.4f

@Keep
@DoNotStrip
class HybridCover : HybridCoverSpec() {
  // Atomic state read by JS-thread getters; written from main only.
  @Volatile
  override var isEnabled: Boolean = false
    private set

  @Volatile
  override var isVisible: Boolean = false
    private set

  // Main-thread-owned state. Setters dispatch to main and mutate here.
  private var backgroundColor: Int = Color.BLACK
  private var imageConfig: ImageConfig? = null
  private var blurStyle: CoverBlurStyle? = null
  private var blurIntensity: Float = DEFAULT_BLUR_INTENSITY

  private var fadeDurationMs: Long = 0
  private var fadeInterpolator: Interpolator = (null as CoverEasing?).toInterpolator()

  private var coverView: View? = null
  /// The activity whose WindowManager owns the currently-mounted cover
  /// panel. Held so removeCoverImmediately can detach from the same
  /// WindowManager it attached to even if the foreground activity has
  /// since changed.
  private var coverHostActivityRef: WeakReference<Activity>? = null
  /// The window token the cover is currently attached to. Used to
  /// detect "we're already attached to the right parent" so
  /// `addCover()` can fast-path to a visibility toggle instead of
  /// rebuilding (which has the addView → first-frame race that
  /// causes the recents thumbnail on Home press to be empty).
  private var coverAttachedToken: IBinder? = null
  /// Window-focus listener installed on the topmost host view while
  /// the cover is mounted. Lives on whichever window the cover is
  /// attached as a sub-window of (activity main, or Modal Dialog if
  /// one is open) so it observes that window's focus state — the
  /// cover panel's own onWindowFocusChanged is unreliable while
  /// FLAG_NOT_FOCUSABLE is set, and the activity's decor doesn't see
  /// focus changes while a Dialog is in front of it.
  private var hostFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
  private var hostFocusView: WeakReference<View>? = null
  private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
  private var systemDialogReceiver: BroadcastReceiver? = null
  /// Dedicated thread for `ACTION_CLOSE_SYSTEM_DIALOGS` dispatch. We
  /// can't afford to wait our turn in the main-thread message queue
  /// before processing the broadcast — on a busy RN main thread the
  /// home-key path can lose its race with the OS recents-thumbnail
  /// capture by tens of milliseconds. Running the receiver on a
  /// foreground-priority side thread lets us decode `reason` and
  /// then jump the main queue (via `postAtFrontOfQueue`) to apply
  /// the alpha=1 toggle.
  private var broadcastThread: HandlerThread? = null
  private var broadcastHandler: Handler? = null
  private var currentActivityRef: WeakReference<Activity>? = null

  /// Count of activities of this app currently in the started state
  /// (i.e. between `onStart` and `onStop`). Used to discriminate true
  /// app backgrounding from intra-app activity transitions:
  ///
  /// - User taps Home → all activities receive `onStop` → count → 0
  ///   → mount cover (backup path; the system broadcast usually wins
  ///   the race).
  /// - User navigates A → B inside the app → B's `onStart` runs
  ///   *before* A's `onStop`, so count goes 1 → 2 → 1, never 0 →
  ///   the backup `onActivityStopped` mount path stays dormant.
  ///
  /// Without this guard, `onActivityStopped(A)` while B is foreground
  /// would mount the cover on top of B and flash during ordinary
  /// forward navigation.
  private var startedActivityCount: Int = 0

  /// Whether the currently-mounted cover should unmount itself when the
  /// activity's window regains focus. True when the cover was mounted by
  /// the system-leave broadcast — handles the "tap our own app from
  /// Recents" case, where neither onResume nor onStart fires because the
  /// activity was never fully paused, only window-focus-shifted to the
  /// Recents overlay. False for manual show()-driven covers, which must
  /// stay up until hide() is called.
  private var coverAutoDismissOnFocus: Boolean = false

  private val mainHandler = Handler(Looper.getMainLooper())
  private var imageLoader: CoverImageLoader? = null

  private fun ensureImageLoader(): CoverImageLoader? {
    imageLoader?.let { return it }
    val ctx: Context = applicationOrNull() ?: NitroModules.applicationContext ?: return null
    val loader = CoverImageLoader(ctx)
    imageLoader = loader
    return loader
  }

  // MARK: - Lifecycle

  override fun enable() {
    mainHandler.post {
      if (isEnabled) return@post
      val app = applicationOrNull() ?: return@post
      isEnabled = true
      // Seed the started-activity count. enable() typically runs at
      // app startup, after the host activity's onStart already fired
      // and before our callbacks were registered — so the bookkeeping
      // starts at 0 even though one activity is actually started. If
      // we left it at 0, a subsequent intra-app navigation A → B
      // would drive the count from 0 (seed) → 1 (B's onStart) → 0
      // (A's onStop), wrongly indicating "app backgrounded" and
      // mounting the cover on top of B.
      //
      // We can't enumerate started activities from the public API, but
      // we have a strong signal that *some* activity is started: a
      // resolvable current activity. That covers the normal startup
      // path. The count self-corrects from there as onStart/onStop
      // pairs fire.
      startedActivityCount = if (resolveActivity() != null) 1 else 0
      val callbacks = createCallbacks()
      lifecycleCallbacks = callbacks
      app.registerActivityLifecycleCallbacks(callbacks)
      registerSystemDialogReceiver(app)
      // Pre-mount the cover invisible. The actual attach + first-frame
      // draw happens NOW, while the activity is fully foregrounded —
      // so when the user hits Home the recents thumbnail snapshot
      // already includes a drawn cover surface and we just toggle
      // alpha. Without this, addView fired from the broadcast handler
      // frequently misses the snapshot because the snapshot is
      // captured before the cover's first frame finishes rendering.
      ensurePreMounted()
    }
  }

  override fun disable() {
    mainHandler.post {
      if (!isEnabled) return@post
      isEnabled = false
      val app = applicationOrNull()
      val callbacks = lifecycleCallbacks
      lifecycleCallbacks = null
      if (app != null && callbacks != null) {
        app.unregisterActivityLifecycleCallbacks(callbacks)
      }
      unregisterSystemDialogReceiver(app)
      imageLoader?.cancelInflight()
      removeCoverImmediately()
      // Reset so the next enable() seeds from a clean slate.
      startedActivityCount = 0
    }
  }

  // MARK: - Mode setters

  override fun setColor(hex: String) {
    val parsed = parseCoverHex(hex) ?: Color.BLACK
    mainHandler.post {
      backgroundColor = parsed
      blurStyle = null
      refreshCoverContentIfMounted()
    }
  }

  override fun setImage(options: CoverImageOptions) {
    val uri = options.uri
    val resize = options.resizeMode ?: CoverResizeMode.CONTAIN
    val x = options.x ?: CoverPositionX.CENTER
    val y = options.y ?: CoverPositionY.CENTER
    val width = (options.width ?: 0.0).coerceAtLeast(0.0)
    val height = (options.height ?: 0.0).coerceAtLeast(0.0)
    mainHandler.post {
      // Reuse the already-decoded bitmap if the URI hasn't changed —
      // setImage is the natural place to re-issue layout fields. A
      // different URI invalidates any in-flight fetch.
      val existing: Bitmap?
      if (imageConfig?.uri == uri) {
        existing = imageConfig?.bitmap
      } else {
        existing = null
        imageLoader?.cancelInflight()
      }
      val config = ImageConfig(
        uri = uri,
        resize = resize,
        x = x,
        y = y,
        width = width,
        height = height,
        bitmap = existing,
      )
      imageConfig = config
      blurStyle = null
      refreshCoverContentIfMounted()
      if (existing == null) {
        // Pass the box size in pixels so the loader can downsample the
        // decoded bitmap. `0` (cover-fills-axis) maps to "use the full
        // screen on that axis", which the loader resolves itself.
        val dm = (resolveActivity()?.resources ?: applicationOrNull()?.resources)
          ?.displayMetrics
        val density = dm?.density ?: 1f
        val targetWPx = if (width > 0) (width * density).toInt() else 0
        val targetHPx = if (height > 0) (height * density).toInt() else 0
        ensureImageLoader()?.load(uri, targetWPx, targetHPx) { bitmap ->
          val current = imageConfig
          if (bitmap != null && current?.uri == uri) {
            imageConfig = current.copy(bitmap = bitmap)
            refreshCoverContentIfMounted()
          }
        }
      }
    }
  }

  override fun clearImage() {
    mainHandler.post {
      imageLoader?.cancelInflight()
      imageConfig = null
      blurStyle = null
      refreshCoverContentIfMounted()
    }
  }

  override fun setBlur(style: CoverBlurStyle, intensity: Double?) {
    val raw = intensity?.toFloat() ?: DEFAULT_BLUR_INTENSITY
    val clamped = raw.coerceIn(0f, 1f)
    mainHandler.post {
      val prevStyle = blurStyle
      blurStyle = style
      blurIntensity = clamped
      // Blur is mutually exclusive with the color/image modes —
      // reset their state so a later setColor/setImage call starts
      // from a clean slate.
      backgroundColor = Color.BLACK
      imageConfig = null
      // Fast path: same style, only intensity changed → mutate the
      // RenderEffect on the existing blur view instead of rebuilding.
      val view = coverView
      if (prevStyle == style && view != null) {
        if (CoverBlurRenderer.updateIntensity(view, style, clamped)) {
          return@post
        }
      }
      refreshCoverContentIfMounted()
    }
  }

  override fun setFade(durationMs: Double, easing: CoverEasing?) {
    val duration = durationMs.toLong().coerceAtLeast(0)
    val interp = easing.toInterpolator()
    mainHandler.post {
      fadeDurationMs = duration
      fadeInterpolator = interp
    }
  }

  // MARK: - Manual show/hide

  override fun show() {
    mainHandler.post {
      coverAutoDismissOnFocus = false
      addCover(animated = true)
    }
  }

  override fun hide() {
    mainHandler.post { removeCover(animated = true) }
  }

  // MARK: - View management (main thread)

  /// Pre-attach the cover at alpha=0 and FLAG_NOT_TOUCHABLE so the
  /// surface is laid out and drawn while the activity is still
  /// foregrounded. Subsequent show/hide calls only toggle alpha and
  /// the touchable flag — no new addView, no first-frame race.
  ///
  /// Idempotent: re-runs whenever the existing pre-mount got
  /// invalidated (orphan after a Modal Dialog closed, or a new
  /// activity instance after config change).
  private fun ensurePreMounted() {
    if (!isEnabled) return
    val activity = resolveActivity() ?: return
    val decor = activity.window?.decorView ?: return
    if (decor.windowToken == null) return

    val current = coverView
    if (current != null) {
      // Still attached to this activity (any token)? Don't disturb —
      // the cover may be currently visible on a Modal Dialog and we
      // don't want to forcibly re-mount on the activity decor.
      val intact = current.windowToken != null &&
        coverHostActivityRef?.get() === activity
      if (intact) return
      // Stale (orphaned by destroyed parent window or different
      // activity instance): clean up the dangling reference.
      detachCoverView()
    }

    attachCover(activity, targetToken = decor.windowToken!!, visible = false, animated = false)
  }

  /// Make the cover visible. If we're already attached to the right
  /// window — i.e. the topmost view in this activity hasn't changed
  /// since pre-mount — this is just a property write (alpha + flag),
  /// which is what makes the home/recents snapshot reliably contain
  /// the cover. If a Modal Dialog has opened in the meantime, the
  /// topmost token is different, so we detach and re-mount on it
  /// (the modal-coverage flow exercises this branch).
  private fun addCover(animated: Boolean) {
    val activity = resolveActivity() ?: run {
      Log.w(TAG, "addCover: no resolvable activity")
      return
    }
    val decor = activity.window?.decorView ?: run {
      Log.w(TAG, "addCover: activity has no decor view")
      return
    }
    if (decor.windowToken == null) {
      Log.w(TAG, "addCover: decor windowToken is null")
      return
    }

    // Pick the topmost host (modal Dialog if open, else activity decor).
    // The same view is also where the auto-dismiss focus listener has to
    // live: while a Dialog is in front, the activity decor's window
    // never sees the focus regain that signals "user came back via
    // Recents tap" — but the Dialog's decor does.
    val topmost = CoverWindowAttachment.topmostHostViewFor(activity, exclude = coverView) ?: decor
    val targetToken = topmost.windowToken ?: decor.windowToken!!

    if (coverView != null && coverAttachedToken === targetToken
      && coverHostActivityRef?.get() === activity
    ) {
      // Fast path: already attached to the right window.
      setCoverVisibility(visible = true, animated = animated)
      installHostFocusListener(topmost)
      return
    }

    // Slow path: re-mount on a different parent (e.g. Modal opened
    // since the pre-mount).
    attachCover(activity, targetToken = targetToken, visible = true, animated = animated)
    installHostFocusListener(topmost)
  }

  /// (Re-)attach the cover view to `targetToken`. Builds a fresh view
  /// reflecting current color/image/blur state so callers don't have
  /// to also call refreshCoverContentIfMounted afterward.
  private fun attachCover(
    activity: Activity,
    targetToken: IBinder,
    visible: Boolean,
    animated: Boolean,
  ) {
    // Detach any existing cover before re-mounting on a new token.
    detachCoverView()

    val view = buildCoverView(activity)
    coverView = view
    coverHostActivityRef = WeakReference(activity)
    coverAttachedToken = targetToken

    // RN's <Modal> on Android creates a separate top-level Dialog
    // window owned by the same activity. That window has a different
    // token than the activity's main window, and TYPE_APPLICATION_PANEL
    // sub-windows of the activity's main window are NOT z-ordered
    // above it. By using `topmostHostViewFor`'s result here we attach
    // the panel as a sub-window of whichever window is on top — main
    // activity, Modal Dialog, popup, etc.
    //
    // FLAG_NOT_FOCUSABLE keeps key-event routing identical (back /
    // volume / IME continue dispatching to whatever owns input
    // focus). FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS make
    // the panel fill the full activity area including under the
    // system bars.
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (!visible) {
      // While invisible, the panel must let touches fall through to
      // the activity. FLAG_NOT_TOUCHABLE achieves that without
      // needing to remove the panel.
      //
      // FLAG_ALT_FOCUSABLE_IM, combined with FLAG_NOT_FOCUSABLE,
      // inverts the panel's relationship with the IME: it tells
      // WindowManager to place the panel BEHIND the soft keyboard
      // instead of over it. Without this, the pre-mounted fullscreen
      // panel sits above the IME's window and intercepts the IME's
      // hit-testing for the focused TextInput beneath, so keystrokes
      // never reach the input. We only want this while invisible —
      // when the cover actually paints (background entry / Cover.show)
      // it must still cover the entire screen including any
      // lingering IME.
      flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    }
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
      flags,
      PixelFormat.TRANSLUCENT,
    )
    params.token = targetToken
    params.gravity = Gravity.TOP or Gravity.START
    // Edge-to-edge: don't let the framework shrink the panel to fit
    // around system bars or display cutouts. Without these,
    // MATCH_PARENT resolves to the activity's content area on
    // edge-to-edge hosts, leaving visible strips behind the status /
    // navigation bars.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      params.fitInsetsTypes = 0
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }

    try {
      activity.windowManager.addView(view, params)
    } catch (e: Throwable) {
      Log.w(TAG, "attachCover: addView failed: $e")
      coverView = null
      coverHostActivityRef = null
      coverAttachedToken = null
      return
    }

    // Run the animation after the addView so the surface is hooked up.
    // For the pre-mount case (visible=false), we just set alpha=0 and
    // don't fire any animator.
    val duration = if (animated && visible) fadeDurationMs else 0L
    if (duration > 0) {
      view.alpha = 0f
      view.animate().cancel()
      view.animate()
        .alpha(1f)
        .setDuration(duration)
        .setInterpolator(fadeInterpolator)
        .start()
    } else {
      view.alpha = if (visible) 1f else 0f
    }

    if (visible) isVisible = true
  }

  /// Toggle the in-place visibility of the already-attached cover —
  /// alpha + FLAG_NOT_TOUCHABLE — without re-attaching to the
  /// WindowManager. This is the fast path that avoids the snapshot
  /// race entirely: the surface has already been drawn, we just
  /// flip a layer alpha and a window flag.
  private fun setCoverVisibility(visible: Boolean, animated: Boolean) {
    val view = coverView ?: return
    val activity = coverHostActivityRef?.get() ?: return
    val params = view.layoutParams as? WindowManager.LayoutParams ?: return

    // Blur mode: re-capture the underlying surface so the bitmap
    // reflects the activity's *current* contents and not a stale
    // snapshot from `setBlur()` time or the previous show. Done
    // synchronously, before the alpha toggle, so the very first
    // visible frame already has fresh blur — otherwise users see a
    // one-frame flash of the previous capture before the refresh
    // lands.
    //
    // The 1/4-scale software draw of the activity decor takes a
    // handful of ms. We can afford it on the home/recents broadcast
    // path because the receiver runs on its own HandlerThread and
    // hops to main with `postAtFrontOfQueue` — by the time we get
    // here we have plenty of headroom before the OS captures the
    // recents thumbnail.
    if (visible) refreshBlurIfActive()

    // Critical-path order on the home/recents broadcast:
    //
    //   1. Set view.alpha (local RenderNode property, applied at the
    //      very next vsync — this is what actually paints the cover
    //      into the next frame's composition, which is what the
    //      recents thumbnail captures).
    //   2. Update FLAG_NOT_TOUCHABLE (binder round-trip to WMS, can
    //      take several ms).
    //
    // updateViewLayout is what gates pass-through of touches while
    // invisible, but the snapshot doesn't care about input routing —
    // only about pixels. Putting alpha first keeps the visual
    // transition off the binder hot path.
    view.animate().cancel()
    view.animate().setListener(null)
    val duration = if (animated) fadeDurationMs else 0L
    val target = if (visible) 1f else 0f
    if (duration > 0 && view.alpha != target) {
      view.animate()
        .alpha(target)
        .setDuration(duration)
        .setInterpolator(fadeInterpolator)
        .start()
    } else {
      view.alpha = target
    }

    // Toggle FLAG_NOT_TOUCHABLE (touch passthrough while invisible) and
    // FLAG_ALT_FOCUSABLE_IM together. The IM-flip is what lets the soft
    // keyboard reach the underlying TextInput while the cover is
    // pre-mounted invisible — without it, the panel sits above the
    // IME and swallows hit-testing for the focused input. When the
    // cover becomes visible we must clear FLAG_ALT_FOCUSABLE_IM again
    // so the cover keeps painting over any lingering IME.
    val invisibleFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
      WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    val newFlags = if (visible) params.flags and invisibleFlags.inv()
                   else params.flags or invisibleFlags
    if (newFlags != params.flags) {
      params.flags = newFlags
      try {
        activity.windowManager.updateViewLayout(view, params)
      } catch (e: Throwable) {
        Log.w(TAG, "setCoverVisibility: updateViewLayout failed: $e")
      }
    }

    isVisible = visible
  }

  /// Find the blur ImageView inside the currently-mounted cover and
  /// re-render the source-content snapshot into it. No-op when blur
  /// mode isn't active or the cover isn't mounted.
  private fun refreshBlurIfActive() {
    val style = blurStyle ?: return
    val activity = coverHostActivityRef?.get() ?: return
    val container = coverView as? ViewGroup ?: return
    val blurView = container.findViewWithTag<ImageView>(BLUR_VIEW_TAG) ?: return
    CoverBlurRenderer.render(blurView, activity, style, blurIntensity)
  }

  /// Watches the topmost host window for focus changes so the cover
  /// can auto-dismiss on return. We can't listen on the cover's own
  /// panel because FLAG_NOT_FOCUSABLE means the panel never gets a
  /// gain-of-focus event; the parent window does. With a Modal Dialog
  /// open, the parent is the Dialog's decor — not the activity's —
  /// because the Dialog is what actually has input focus, so listening
  /// on the activity decor never fires when the user comes back from
  /// Recents (the Dialog's focus state is what changes).
  private fun installHostFocusListener(host: View) {
    if (hostFocusView?.get() === host && hostFocusListener != null) return
    uninstallHostFocusListener()
    val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
      if (coverAutoDismissOnFocus && hasFocus) {
        removeCover(animated = true)
      }
    }
    host.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    hostFocusListener = listener
    hostFocusView = WeakReference(host)
  }

  private fun uninstallHostFocusListener() {
    val listener = hostFocusListener ?: return
    hostFocusListener = null
    val host = hostFocusView?.get()
    hostFocusView = null
    if (host == null) return
    try {
      host.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
    } catch (_: Throwable) {
      // ViewTreeObserver may already be detached; safe to ignore.
    }
  }

  /// Hide the cover. While the module is enabled, we keep the panel
  /// attached so the next show() / Home press is a fast property
  /// toggle. On disable we go through removeCoverImmediately, which
  /// actually detaches.
  private fun removeCover(animated: Boolean) {
    if (coverView == null) {
      isVisible = false
      return
    }
    if (!isEnabled) {
      // No persistent attachment when disabled; tear down for real.
      if (!animated || fadeDurationMs <= 0) {
        removeCoverImmediately()
        return
      }
      val view = coverView ?: return
      view.animate().cancel()
      view.animate()
        .alpha(0f)
        .setDuration(fadeDurationMs)
        .setInterpolator(fadeInterpolator)
        .setListener(object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            view.animate().setListener(null)
            if (coverView === view) removeCoverImmediately()
            else (view.parent as? ViewGroup)?.removeView(view)
          }
        })
        .start()
      return
    }
    // Enabled: keep the panel attached but invisible (FLAG_NOT_TOUCHABLE
    // + alpha=0). The host focus listener can stay — it only acts when
    // coverAutoDismissOnFocus is set, which the next user-leave will
    // re-arm anyway.
    setCoverVisibility(visible = false, animated = animated)
  }

  private fun removeCoverImmediately() {
    uninstallHostFocusListener()
    detachCoverView()
    isVisible = false
  }

  /// Pure detach helper — no state-machine logic. Used by both
  /// removeCoverImmediately (full teardown) and attachCover (re-mount
  /// on a new parent token).
  private fun detachCoverView() {
    val view = coverView ?: return
    view.animate().cancel()
    view.animate().setListener(null)
    val activity = coverHostActivityRef?.get()
    try {
      activity?.windowManager?.removeView(view)
    } catch (_: IllegalArgumentException) {
      // Panel was already detached (e.g. host activity finished).
    }
    coverView = null
    coverHostActivityRef = null
    coverAttachedToken = null
  }

  private fun refreshCoverContentIfMounted() {
    val activity = coverHostActivityRef?.get() ?: return
    if (coverView == null) return
    val decor = activity.window?.decorView ?: return
    // Re-evaluate the topmost host instead of reusing the cached
    // `coverAttachedToken` — the previous parent may have been
    // destroyed (e.g. a Modal that the cover was attached to has
    // since dismissed), in which case re-attaching to the stale
    // token would throw and silently leave the cover unmounted.
    val topmost = CoverWindowAttachment.topmostHostViewFor(activity, exclude = coverView) ?: decor
    val targetToken = topmost.windowToken ?: decor.windowToken ?: return
    attachCover(activity, targetToken = targetToken, visible = isVisible, animated = false)
  }

  private fun buildCoverView(activity: Activity): View {
    val container = FrameLayout(activity).apply {
      isClickable = true
      isFocusable = true
      contentDescription = COVER_LABEL
      tag = COVER_TAG
      // Prevent VoiceOver/TalkBack from focusing the brief overlay.
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
      setOnTouchListener { _, _ -> true }
    }

    val style = blurStyle
    if (style != null) {
      val blurView = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(style.fallbackColor())
        tag = BLUR_VIEW_TAG
      }
      CoverBlurRenderer.render(blurView, activity, style, blurIntensity)
      container.addView(
        blurView,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT,
        ),
      )
      return container
    }

    container.setBackgroundColor(backgroundColor)

    val config = imageConfig
    if (config != null) {
      val imageView = ImageView(activity).apply {
        scaleType = config.resize.toScaleType()
        setImageBitmap(config.bitmap)
      }
      val gravity = computeGravity(config.x, config.y, config.resize)
      val density = activity.resources.displayMetrics.density
      val w = if (config.width > 0) (config.width * density).toInt()
              else FrameLayout.LayoutParams.MATCH_PARENT
      val h = if (config.height > 0) (config.height * density).toInt()
              else FrameLayout.LayoutParams.MATCH_PARENT
      container.addView(
        imageView,
        FrameLayout.LayoutParams(w, h, gravity),
      )
      // No fallback fetch here: setImage already kicked off the load
      // when the URI was first set, and refreshCoverContentIfMounted
      // is re-invoked from its completion callback.
    }
    return container
  }

  private fun computeGravity(
    x: CoverPositionX,
    y: CoverPositionY,
    resize: CoverResizeMode,
  ): Int {
    if (resize == CoverResizeMode.STRETCH || resize == CoverResizeMode.COVER) {
      return Gravity.CENTER
    }
    val hg = when (x) {
      CoverPositionX.LEFT -> Gravity.LEFT
      CoverPositionX.CENTER -> Gravity.CENTER_HORIZONTAL
      CoverPositionX.RIGHT -> Gravity.RIGHT
    }
    val vg = when (y) {
      CoverPositionY.TOP -> Gravity.TOP
      CoverPositionY.CENTER -> Gravity.CENTER_VERTICAL
      CoverPositionY.BOTTOM -> Gravity.BOTTOM
    }
    return hg or vg
  }

  // MARK: - Activity tracking

  private fun createCallbacks(): Application.ActivityLifecycleCallbacks =
    object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        currentActivityRef = WeakReference(activity)
      }

      override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        startedActivityCount += 1
        // Re-attach the pre-mounted cover when the activity changes
        // (e.g. config change, navigation between activities). The
        // old token belonged to a now-destroyed window so the surface
        // for it is gone.
        if (coverHostActivityRef?.get() !== activity) {
          detachCoverView()
        }
        ensurePreMounted()
      }

      override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        // Window's decor is guaranteed attached by onResume; if
        // pre-mount missed it on Started (token was null), retry.
        ensurePreMounted()
        removeCover(animated = true)
      }

      override fun onActivityPaused(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        // No mount here — the system broadcast for Home / Recents /
        // Assist is the authoritative leave signal. onPause also
        // fires for permission dialogs, biometric prompts, the
        // notification shade, and the volume slider, which is what
        // caused the cover to flash on top of system UI.
      }

      override fun onActivityStopped(activity: Activity) {
        // Decrement first so the "did the app actually background?"
        // check below sees the post-stop count. Floor at 0 in case
        // the callback is invoked for an activity we never saw start
        // (e.g. enable() ran mid-lifecycle).
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)

        // Backup show: ACTION_CLOSE_SYSTEM_DIALOGS is the early
        // signal that lets the cover land in the recents thumbnail,
        // but on some devices / Android versions the broadcast is
        // flaky for the home key. onActivityStopped fires ONLY when
        // the activity is fully no longer visible — i.e. NOT for
        // permission dialogs, biometric prompts, the notification
        // shade, or the volume slider.
        //
        // BUT: it also fires for ordinary intra-app navigation when
        // activity A stops because activity B has just been started
        // in front of it. In that case `startedActivityCount` stays
        // > 0 (B's onStart already ran and incremented it before
        // A's onStop), and we must NOT mount the cover — doing so
        // would flash on top of B during a normal forward navigation.
        // Only treat this as a real backgrounding when the count
        // has reached zero.
        if (startedActivityCount == 0 && !isVisible) {
          coverAutoDismissOnFocus = true
          addCover(animated = false)
        }
      }
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    }

  /// Listens for ACTION_CLOSE_SYSTEM_DIALOGS, the protected system
  /// broadcast PhoneWindowManager dispatches just before pausing the
  /// foreground activity for a user-initiated app leave (Home, Recents,
  /// Assist, app-switcher gesture). The broadcast is delivered before
  /// onPause and before the OS captures the Recents thumbnail, so the
  /// cover lands in the snapshot. Permission dialogs, biometric prompts,
  /// the notification shade, the volume slider, and other transient
  /// focus losses do NOT fire this broadcast — that's what cleanly
  /// distinguishes "user actually left" from "something briefly took
  /// focus", without any host-app wiring.
  ///
  /// The `reason` extras come from PhoneWindowManager and are stable
  /// across AOSP releases but are not formally part of the public API.
  ///
  /// `ACTION_CLOSE_SYSTEM_DIALOGS` is marked `@Deprecated` for *senders*
  /// (apps can no longer broadcast it), but receiving it from the system
  /// is the documented integration point. Suppressing the deprecation
  /// here keeps build logs clean.
  @Suppress("DEPRECATION")
  private fun registerSystemDialogReceiver(app: Application) {
    val thread = HandlerThread("CoverSystemDialogReceiver", Process.THREAD_PRIORITY_FOREGROUND)
    thread.start()
    val handler = Handler(thread.looper)
    broadcastThread = thread
    broadcastHandler = handler

    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
        val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY)
        Log.i(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS reason=$reason")
        if (reason !in USER_LEAVE_REASONS) return
        // Hop to main at the *front* of its message queue. addCover
        // ultimately writes view.alpha and a WindowManager flag, both
        // of which require the main thread. On a loaded RN main
        // thread the default postAtBack of the queue can sit behind
        // tens of ms of UI work — long enough for the OS to capture
        // the recents thumbnail before our toggle lands. Front-of-
        // queue can't preempt whatever message is currently running,
        // but it skips ahead of every queued message, which is the
        // dominant source of latency we measured on the home-key
        // path. The receiver itself is on a side thread, so we don't
        // pay a main-queue wait just to read `reason`.
        mainHandler.postAtFrontOfQueue {
          // The broadcast receiver runs on a side thread, then hops
          // here. `disable()` may have run on main between those two
          // hops — clearing receivers, lifecycle callbacks, and the
          // cover state — and we must not resurrect a mount in that
          // window.
          if (!isEnabled) return@postAtFrontOfQueue
          coverAutoDismissOnFocus = true
          addCover(animated = false)
        }
      }
    }
    val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      // Android 13+ requires an export flag for runtime-registered
      // receivers. ACTION_CLOSE_SYSTEM_DIALOGS is a protected
      // broadcast (only the system can send it), so EXPORTED is
      // safe and matches the historical implicit-export behavior.
      // The Handler argument routes onReceive to our side thread.
      app.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag")
      app.registerReceiver(receiver, filter, null, handler)
    }
    systemDialogReceiver = receiver
  }

  private fun unregisterSystemDialogReceiver(app: Application?) {
    val receiver = systemDialogReceiver
    systemDialogReceiver = null
    if (app != null && receiver != null) {
      try {
        app.unregisterReceiver(receiver)
      } catch (_: IllegalArgumentException) {
        // Receiver was already unregistered (e.g., process tear-down).
      }
    }
    broadcastHandler = null
    broadcastThread?.quitSafely()
    broadcastThread = null
  }

  private fun applicationOrNull(): Application? {
    val ctx = NitroModules.applicationContext ?: return null
    return ctx.applicationContext as? Application
  }

  private fun resolveActivity(): Activity? =
    currentActivityRef?.get() ?: NitroModules.applicationContext?.currentActivity

  companion object {
    private const val TAG = "Cover"
    private const val COVER_TAG = "CoverOverlay"
    private const val COVER_LABEL = "Privacy cover"

    /// Extras key on ACTION_CLOSE_SYSTEM_DIALOGS. Defined as
    /// PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY in AOSP but not
    /// exposed in the SDK, so we hard-code the literal.
    private const val SYSTEM_DIALOG_REASON_KEY = "reason"

    /// `reason` values for which the broadcast indicates a user-
    /// initiated app leave that warrants mounting the cover. Other
    /// known reasons ("globalactions" for the power menu,
    /// "voiceinteraction" for some assistant flows, "fs_dialog" for
    /// transient system dialogs) are deliberately excluded.
    private val USER_LEAVE_REASONS = setOf("homekey", "recentapps", "assist")
  }
}
