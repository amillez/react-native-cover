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
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
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

  /// Set by the ACTION_CLOSE_SYSTEM_DIALOGS receiver (on a side thread)
  /// *before* it queues the alpha-toggle onto main. Read by both the
  /// broadcast's main-thread post and `onActivityPaused`; whichever
  /// fires first does the mount and clears the flag, the other no-ops.
  ///
  /// This dual-entry design closes the race where `postAtFrontOfQueue`
  /// still sits behind the currently-running main-thread message and
  /// lands *after* the OS has captured the recents thumbnail. The
  /// lifecycle dispatch into `onActivityPaused` runs as part of
  /// `Activity.performPause` — before the framework reports paused
  /// state to WMS, which is what triggers snapshot capture — so
  /// mounting synchronously from there is guaranteed to land in the
  /// surface before the snapshot. The broadcast post is still required
  /// for the inverse race (broadcast delivered slightly after onPause
  /// on some builds) and the rare path where no activity pause occurs.
  ///
  /// Filter-safe: only the homekey / recentapps / assist broadcast
  /// reasons set this. Transient pauses (permission dialog, biometric
  /// prompt, notification shade, volume slider) don't fire the
  /// broadcast, so the flag stays false and `onActivityPaused` no-ops
  /// — preserving the false-positive avoidance that motivated keeping
  /// the lifecycle callback empty originally.
  @Volatile
  private var pendingUserLeaveMount: Boolean = false

  /// SurfaceControl backing the cover panel's window. Captured via
  /// reflection (see `SurfaceControlAccess`) after the first traversal
  /// completes, so we can apply alpha=1 directly via
  /// `SurfaceControl.Transaction` from the broadcast's HandlerThread —
  /// no main-thread hop, no ViewRootImpl traversal in the critical
  /// path. Cleared on detach.
  ///
  /// The direct setAlpha is transient: the next ViewRootImpl traversal
  /// (whenever it fires) will re-apply the View tree's alpha to the
  /// SurfaceControl. So the main-thread path must STILL eventually set
  /// `view.alpha = 1` to keep them consistent — but the snapshot
  /// race is won as soon as the direct transaction is applied at the
  /// next SurfaceFlinger compose, which is independent of main.
  @Volatile
  private var coverSurfaceControl: SurfaceControl? = null

  /// Tracks the last-applied SCVH alpha so animation reads / status
  /// queries don't need to round-trip through SurfaceFlinger. Updated
  /// in every `trySetScvhAlpha` and in `animateScvhAlpha`'s per-frame
  /// callback. Main-thread writes from animation, broadcast-thread
  /// writes from fast-show — `@Volatile` so both see consistent.
  @Volatile
  private var scvhAlphaState: Float = 0f

  /// In-flight SCVH alpha animator, if any. Held so a new visibility
  /// toggle can cancel a still-running fade before starting its own.
  private var scvhAnimator: android.animation.ValueAnimator? = null

  /// API 30+ fast-path. The cover content (FrameLayout with color /
  /// image / blur) is hosted inside a `SurfaceControlViewHost`, which
  /// renders it into a `SurfaceControl` WE own. The cover Window's
  /// root view is just a `SurfaceView` that reparents the host's
  /// `SurfacePackage` for compositing.
  ///
  /// Why this matters for the snapshot race:
  ///
  ///   - On `ACTION_CLOSE_SYSTEM_DIALOGS` we call
  ///     `SurfaceControl.Transaction().setAlpha(scvhSurfaceControl, 1f).apply()`
  ///     directly from the broadcast HandlerThread.
  ///   - The transaction goes to SurfaceFlinger and is picked up on
  ///     the very next compose — no main-thread hop, no
  ///     `ViewRootImpl` traversal, no buffer re-render (the buffer
  ///     was already rendered at attach with `view.alpha = 1`).
  ///   - Latency drops from ~2 vsyncs (current view.alpha pipeline)
  ///     to ~1 vsync (compose only).
  ///
  /// Held weakly via a regular field because `SurfaceControlViewHost`
  /// is API 30+; lower API paths use `coverContent` directly without
  /// SCVH. Released in `detachCoverView`.
  private var scvhHost: SurfaceControlViewHost? = null

  /// SurfaceControl owned by `scvhHost`. Captured at SCVH creation, so
  /// it's available immediately from any thread (unlike the View-tree
  /// SurfaceControl which needs a post-attach reflection step).
  @Volatile
  private var scvhSurfaceControl: SurfaceControl? = null

  /// The cover content view (FrameLayout with current color / image /
  /// blur state). On API 30+ this is hosted in `scvhHost` and rendered
  /// into the SCVH's surface (separate from `coverView`'s window). On
  /// lower API paths this is the same as `coverView`. Tracked
  /// separately so content refreshes (`setColor` etc.) can locate the
  /// FrameLayout to update without going through the window root.
  private var coverContent: View? = null

  /// Listener installed on the host activity's decor view (NOT the
  /// cover's parent) so we can detect a Modal Dialog opening BEFORE
  /// the user backgrounds the app. When the activity loses focus
  /// because a Modal is now the topmost window in our process, we
  /// proactively re-attach the cover to the modal's token. Otherwise
  /// the first home-press after a modal opens loses the snapshot
  /// race — the cover sits below the modal at compose time, and the
  /// reactive re-attach inside `addCover` lands too late.
  ///
  /// Separate from `hostFocusListener`, which is installed on the
  /// cover's PARENT host view (modal or activity decor) and only
  /// drives auto-dismiss when the cover is already shown.
  private var activityFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
  private var activityFocusDecor: WeakReference<View>? = null

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
      uninstallActivityFocusListener()
      removeCoverImmediately()
      // Reset so the next enable() seeds from a clean slate.
      startedActivityCount = 0
      pendingUserLeaveMount = false
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
  ///
  /// Also wires up the activity focus listener that drives proactive
  /// modal-token reparenting (see `ensureCoverOnTopmost`).
  private fun ensurePreMounted() {
    if (!isEnabled) return
    val activity = resolveActivity() ?: return
    val decor = activity.window?.decorView ?: return
    if (decor.windowToken == null) return

    installActivityFocusListener(decor)

    ensureCoverOnTopmost()
  }

  /// Make sure the cover is pre-mounted on the CURRENT topmost host
  /// window in this process. Called at enable time, on every activity
  /// lifecycle event, AND whenever the activity's decor window focus
  /// changes (which fires when a Modal Dialog gets added/removed in
  /// the same process). The latter case is what fixes the
  /// "first home-press with a modal open misses the cover" bug:
  /// without proactive re-attach, the cover sits below the modal at
  /// compose time when the leave-broadcast fires.
  ///
  /// No-op while the cover is currently visible — re-attaching mid-
  /// show would tear down the visible cover. The reactive path in
  /// `addCover` still handles tokens that change after this point.
  private fun ensureCoverOnTopmost() {
    if (!isEnabled) return
    if (isVisible) return
    val activity = resolveActivity() ?: return
    val decor = activity.window?.decorView ?: return
    if (decor.windowToken == null) return

    val topmost = CoverWindowAttachment.topmostHostViewFor(activity, exclude = coverView) ?: decor
    val targetToken = topmost.windowToken ?: decor.windowToken!!

    val current = coverView
    if (current != null
      && current.windowToken != null
      && coverAttachedToken === targetToken
      && coverHostActivityRef?.get() === activity
    ) {
      return  // already on the correct token
    }
    Log.i(TAG, "ensureCoverOnTopmost: reparent (topmost=${topmost.javaClass.simpleName})")
    attachCover(activity, targetToken = targetToken, visible = false, animated = false)
  }

  private fun installActivityFocusListener(decor: View) {
    if (activityFocusDecor?.get() === decor && activityFocusListener != null) return
    uninstallActivityFocusListener()
    val listener = ViewTreeObserver.OnWindowFocusChangeListener { _ ->
      // Focus on the activity's decor window flips whenever a top-
      // level view in this process gains or loses focus — Modal
      // Dialog open, Modal close, popup window, etc. Re-resolve
      // topmost and reparent the (invisible) pre-mounted cover so
      // it's always sitting above whatever the user will see next.
      ensureCoverOnTopmost()
    }
    try {
      decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    } catch (e: Throwable) {
      Log.w(TAG, "installActivityFocusListener: $e")
      return
    }
    activityFocusListener = listener
    activityFocusDecor = WeakReference(decor)
  }

  private fun uninstallActivityFocusListener() {
    val listener = activityFocusListener ?: return
    activityFocusListener = null
    val decor = activityFocusDecor?.get()
    activityFocusDecor = null
    if (decor == null) return
    try {
      decor.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
    } catch (_: Throwable) {
      // VTO may already be detached; safe to ignore.
    }
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

    val content = buildCoverView(activity)
    coverContent = content

    // API 30+ fast path: host the cover content inside a
    // SurfaceControlViewHost so we own the SurfaceControl, which lets
    // the broadcast HandlerThread apply alpha=1 via a Transaction
    // directly — skipping the View-tree → traversal → next vsync
    // dependency that we can't escape on this device's hidden-API-
    // restricted ViewRootImpl reflection. The cover Window's root
    // becomes a SurfaceView that reparents the SCVH's SurfacePackage.
    //
    // The content view keeps `view.alpha = 1` so SCVH renders the
    // FrameLayout (and its RenderEffect blur) into the SCVH's
    // surface buffer once. Subsequent visibility toggles only
    // change the SC's alpha at the SurfaceFlinger compose level,
    // never re-render the buffer.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val display: Display? = activity.display ?: activity.windowManager.defaultDisplay
      if (display != null && tryAttachCoverViaScvh(activity, content, display, targetToken, visible, animated)) {
        return
      }
      // Fall through to legacy path on any SCVH failure.
    }

    val view: View = content
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
    // Ask the OS to compose at the display's highest supported refresh
    // rate while the cover panel is attached. Shorter vsync interval ⇒
    // less wall-clock time between our `view.alpha = 1` and the next
    // SurfaceFlinger compose that lands the alpha=1 buffer for the
    // recents-thumbnail snapshot. At 120Hz the pipeline (2 vsyncs ≈
    // 17 ms) fits inside the snapshot race window observed on tight
    // devices; at 60Hz (33 ms) it doesn't. No-op on panels that don't
    // support a higher rate.
    val maxRate = activity.windowManager.defaultDisplay
      ?.supportedModes
      ?.maxOfOrNull { it.refreshRate }
      ?: 0f
    if (maxRate > 0f) {
      params.preferredRefreshRate = maxRate
      Log.i(TAG, "attachCover: preferredRefreshRate=$maxRate (modes=${
        activity.windowManager.defaultDisplay?.supportedModes?.joinToString { "${it.refreshRate}Hz" }
      })")
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

    // Capture the SurfaceControl once ViewRootImpl has wired one up.
    // The first traversal/layout has to complete before
    // ViewRootImpl.mSurfaceControl is set — using `view.post` runs
    // the resolver on the next main-thread tick, which is after the
    // first vsync/traversal kicked off by addView. Re-runs on every
    // attach because each addView creates a new ViewRootImpl with its
    // own SurfaceControl.
    view.post {
      if (coverView !== view) return@post  // Detached / re-attached since.
      coverSurfaceControl = SurfaceControlAccess.getSurfaceControl(view)
      Log.i(TAG, "attachCover: SurfaceControl captured=${coverSurfaceControl != null}")
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

  /// SCVH-backed attach. Returns true on success; on any failure we
  /// release partial state and return false so the caller falls
  /// through to the legacy `view.alpha` path.
  ///
  /// Layout: cover Window's root is a `SurfaceView`. The SurfaceView
  /// reparents an SCVH `SurfacePackage`, which is rendered into a
  /// SurfaceControl we own. The cover content (color/image/blur)
  /// lives inside the SCVH at `view.alpha = 1` always, so the SCVH's
  /// surface buffer is full-strength once rendered. Visibility is
  /// purely a SurfaceFlinger-level alpha multiplier on our SC.
  private fun tryAttachCoverViaScvh(
    activity: Activity,
    content: View,
    display: Display,
    targetToken: IBinder,
    visible: Boolean,
    animated: Boolean,
  ): Boolean {
    val displayMetrics = activity.resources.displayMetrics
    val width = displayMetrics.widthPixels
    val height = displayMetrics.heightPixels
    if (width <= 0 || height <= 0) {
      Log.w(TAG, "attachCover scvh: bad display size ${width}x${height}")
      return false
    }

    val host = try {
      SurfaceControlViewHost(activity, display, null as IBinder?)
    } catch (e: Throwable) {
      Log.w(TAG, "attachCover scvh: SurfaceControlViewHost ctor failed: $e")
      return false
    }
    try {
      host.setView(content, width, height)
    } catch (e: Throwable) {
      Log.w(TAG, "attachCover scvh: setView failed: $e")
      host.release()
      return false
    }

    val pkg = host.surfacePackage
    if (pkg == null) {
      Log.w(TAG, "attachCover scvh: surfacePackage is null")
      host.release()
      return false
    }
    val sc = pkg.surfaceControl

    // Pre-set the SC's alpha so the first compose after add reflects
    // the intended visibility — no flash for pre-mount (alpha=0)
    // and no extra Transaction roundtrip for the rare attachCover
    // with visible=true.
    try {
      SurfaceControl.Transaction()
        .setAlpha(sc, if (visible) 1f else 0f)
        .apply()
    } catch (e: Throwable) {
      Log.w(TAG, "attachCover scvh: initial setAlpha failed: $e")
      host.release()
      return false
    }

    val surfaceView = SurfaceView(activity).apply {
      // SurfaceView's underlying surface is composed below the host
      // window content by default. The cover window has no other
      // content (just this SurfaceView), so it doesn't matter for
      // visibility — but setZOrderOnTop(true) avoids a stale-frame
      // hole-punch issue some devices show during the first compose
      // after add.
      setZOrderOnTop(true)
      try {
        setChildSurfacePackage(pkg)
      } catch (e: Throwable) {
        Log.w(TAG, "attachCover scvh: setChildSurfacePackage failed: $e")
        host.release()
        return false
      }
    }

    val params = buildCoverWindowParams(activity, targetToken, visible)

    try {
      activity.windowManager.addView(surfaceView, params)
    } catch (e: Throwable) {
      Log.w(TAG, "attachCover scvh: addView failed: $e")
      host.release()
      return false
    }

    coverView = surfaceView
    coverHostActivityRef = WeakReference(activity)
    coverAttachedToken = targetToken
    scvhHost = host
    scvhSurfaceControl = sc

    if (visible) isVisible = true
    Log.i(TAG, "attachCover scvh: attached size=${width}x${height} visible=$visible sc=ok")
    return true
  }

  /// Shared LayoutParams builder for the cover Window. Used by both
  /// the SCVH (SurfaceView-rooted) and legacy (FrameLayout-rooted)
  /// attach paths.
  private fun buildCoverWindowParams(
    activity: Activity,
    targetToken: IBinder,
    visible: Boolean,
  ): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (!visible) {
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
    val maxRate = activity.windowManager.defaultDisplay
      ?.supportedModes
      ?.maxOfOrNull { it.refreshRate }
      ?: 0f
    if (maxRate > 0f) {
      params.preferredRefreshRate = maxRate
    }
    return params
  }

  /// Apply alpha to the SCVH-owned SurfaceControl via a Transaction.
  /// Safe to call from any thread; takes effect on the next
  /// SurfaceFlinger compose with no traversal in the critical path.
  ///
  /// Returns true iff an SC was available and the transaction was
  /// applied. Callers should fall back to the View-tree alpha path
  /// when this returns false (e.g. legacy attach, pre-API-30, or
  /// SCVH creation failed earlier).
  private fun trySetScvhAlpha(alpha: Float): Boolean {
    val sc = scvhSurfaceControl ?: return false
    return try {
      SurfaceControl.Transaction()
        .setAlpha(sc, alpha)
        .apply()
      scvhAlphaState = alpha
      true
    } catch (e: Throwable) {
      Log.w(TAG, "trySetScvhAlpha($alpha) failed: $e")
      false
    }
  }

  /// Animate the SCVH SC's alpha from its current value to `target`
  /// over `duration`. Uses a ValueAnimator + per-frame
  /// `SurfaceControl.Transaction` (one binder per ~16 ms). Runs on
  /// main; cancels any in-flight animation before starting.
  private fun animateScvhAlpha(sc: SurfaceControl, target: Float, duration: Long) {
    scvhAnimator?.cancel()
    val from = scvhAlphaState
    if (from == target) return
    val animator = android.animation.ValueAnimator.ofFloat(from, target).apply {
      this.duration = duration
      interpolator = fadeInterpolator
      addUpdateListener { va ->
        val a = va.animatedValue as Float
        try {
          SurfaceControl.Transaction().setAlpha(sc, a).apply()
          scvhAlphaState = a
        } catch (_: Throwable) {
          // Surface gone mid-animation — just stop trying.
          cancel()
        }
      }
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          if (scvhAnimator === this@apply) scvhAnimator = null
        }
        override fun onAnimationCancel(animation: Animator) {
          if (scvhAnimator === this@apply) scvhAnimator = null
        }
      })
    }
    scvhAnimator = animator
    animator.start()
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
    val scvh = scvhSurfaceControl
    Log.i(TAG, "setCoverVisibility: visible=$visible animated=$animated scvh=${scvh != null} t=${SystemClock.uptimeMillis()}")

    // Blur mode: re-capture the underlying surface so the bitmap
    // reflects the activity's *current* contents and not a stale
    // snapshot from `setBlur()` time or the previous show.
    if (visible) refreshBlurIfActive()

    val duration = if (animated) fadeDurationMs else 0L
    val target = if (visible) 1f else 0f

    if (scvh != null) {
      // SCVH fast path: SurfaceControl alpha via Transaction. Skips
      // the View-tree → ViewRootImpl traversal → next-vsync chain
      // entirely. The transaction is applied at the next
      // SurfaceFlinger compose, which is the same frame that
      // contains the recents-thumbnail snapshot pixels — this is
      // what gives us the deterministic win over the legacy
      // view.alpha path on devices where reflection is blocked and
      // the snapshot fires inside the first vsync after onPause.
      if (duration > 0 && scvhAlphaState != target) {
        animateScvhAlpha(scvh, target, duration)
      } else {
        scvhAnimator?.cancel()
        trySetScvhAlpha(target)
      }
    } else {
      // Legacy path: View.alpha on the cover Window's root view.
      // Subject to the next-vsync race; works on every API but loses
      // intermittently on devices with tight snapshot timing.
      view.animate().cancel()
      view.animate().setListener(null)
      if (duration > 0 && view.alpha != target) {
        view.animate()
          .alpha(target)
          .setDuration(duration)
          .setInterpolator(fadeInterpolator)
          .start()
      } else {
        view.alpha = target
      }
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
    // Look up the blur ImageView on `coverContent` (the FrameLayout
    // with cover state) rather than `coverView` — on the SCVH path
    // `coverView` is the SurfaceView wrapper, not the content tree.
    val container = (coverContent ?: coverView) as? ViewGroup ?: return
    val blurView = container.findViewWithTag<ImageView>(BLUR_VIEW_TAG) ?: return
    // Pass `coverView` so the blur capture skips our own cover-window
    // root. On the SCVH path that root is a SurfaceView whose content
    // lives in a separate hardware surface; software-drawing it would
    // yield a transparent bitmap and the blur cover would never
    // become opaque. On the legacy path `coverView` is the same
    // FrameLayout that `target.rootView` already excludes, so passing
    // it again is a harmless no-op.
    CoverBlurRenderer.render(blurView, activity, style, blurIntensity, alsoExclude = coverView)
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
    scvhAnimator?.cancel()
    scvhAnimator = null
    view.animate().cancel()
    view.animate().setListener(null)
    val activity = coverHostActivityRef?.get()
    try {
      activity?.windowManager?.removeView(view)
    } catch (_: IllegalArgumentException) {
      // Panel was already detached (e.g. host activity finished).
    }
    // Release the SCVH host AFTER removeView. SCVH's SurfacePackage
    // was reparented into the SurfaceView, which is now detached, so
    // it's safe to tear down the host and let SF reclaim the SC.
    scvhHost?.let { host ->
      try {
        host.release()
      } catch (_: Throwable) {
        // Best-effort; host may already be invalidated.
      }
    }
    scvhHost = null
    scvhSurfaceControl = null
    scvhAlphaState = 0f
    coverView = null
    coverContent = null
    coverHostActivityRef = null
    coverAttachedToken = null
    coverSurfaceControl = null
  }

  /// Apply alpha to the cover's `SurfaceControl` directly via a
  /// `Transaction`, bypassing the View pipeline entirely. Safe to call
  /// from ANY thread (Transaction.apply is thread-safe).
  ///
  /// Returns true if a transaction was applied. The next SurfaceFlinger
  /// compose will reflect the new alpha — independent of whether the
  /// RN main thread is busy or when ViewRootImpl next traverses. False
  /// if the SC isn't captured yet (pre-first-frame, or reflection
  /// disabled) — caller falls back to the View-tree path.
  private fun trySetSurfaceAlphaFast(alpha: Float): Boolean {
    val sc = coverSurfaceControl ?: return false
    return try {
      SurfaceControl.Transaction()
        .setAlpha(sc, alpha)
        .apply()
      true
    } catch (e: Throwable) {
      Log.w(TAG, "trySetSurfaceAlphaFast failed: $e")
      false
    }
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
        // Synchronous mount path for user-initiated app leaves
        // (homekey / recentapps / assist). The system broadcast
        // sets `pendingUserLeaveMount` *before* it hops to main,
        // then queues an alpha-toggle via `postAtFrontOfQueue`. On
        // a busy RN main thread that queued post can still land
        // after the OS captures the recents thumbnail — front-of-
        // queue skips ahead of *queued* messages but can't preempt
        // whatever is currently running. This callback, by
        // contrast, runs inside `Activity.performPause` itself,
        // before the framework reports paused state to WMS (which
        // is what triggers the snapshot). So mounting from here
        // when the flag is set guarantees alpha=1 is in the
        // surface before the snapshot capture.
        //
        // Permission dialogs, biometric prompts, the notification
        // shade, and the volume slider also fire `onPause` — and
        // mounting then is what used to cause the cover to flash
        // on top of system UI. They do NOT fire the broadcast,
        // so the flag stays false in those cases and we no-op,
        // preserving the original filter behavior.
        Log.i(TAG, "onPause: enabled=$isEnabled pending=$pendingUserLeaveMount isVisible=$isVisible t=${SystemClock.uptimeMillis()}")
        if (isEnabled && pendingUserLeaveMount && !isVisible) {
          performUserLeaveMount()
        }
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
        Log.i(TAG, "onStopped: count=$startedActivityCount isVisible=$isVisible t=${SystemClock.uptimeMillis()}")
        if (startedActivityCount == 0 && !isVisible) {
          coverAutoDismissOnFocus = true
          addCover(animated = false)
        }
      }
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    }

  /// Shared mount path for the system-leave signal. Invoked from both
  /// `onActivityPaused` (synchronous, before the snapshot is taken)
  /// and the broadcast's `postAtFrontOfQueue` handler (fallback when
  /// pause hasn't reached us yet). Clears the pending flag first so
  /// the other entry no-ops when it eventually runs.
  private fun performUserLeaveMount() {
    Log.i(TAG, "performUserLeaveMount t=${SystemClock.uptimeMillis()}")
    pendingUserLeaveMount = false
    coverAutoDismissOnFocus = true
    addCover(animated = false)
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
        val recvAt = SystemClock.uptimeMillis()
        Log.i(TAG, "broadcast: reason=$reason isEnabled=$isEnabled isVisible=$isVisible sc=${coverSurfaceControl != null} t=$recvAt")
        if (reason !in USER_LEAVE_REASONS) return
        if (!isEnabled) return

        // FAST PATH: apply alpha=1 directly to the cover's
        // SurfaceControl from this (side) thread. SurfaceControl
        // transactions are thread-safe, atomic at the next
        // SurfaceFlinger compose, and require no main-thread hop —
        // so this lands before the recents-thumbnail snapshot
        // regardless of how busy the RN main thread is. Two
        // possible SCs:
        //   - `scvhSurfaceControl`: from `SurfaceControlViewHost`
        //     (API 30+, public API, no reflection required). This
        //     is the preferred path.
        //   - `coverSurfaceControl`: reflectively captured from
        //     ViewRootImpl. Subject to hidden-API enforcement;
        //     fails on many shipping devices. Kept as a
        //     belt-and-suspenders fallback.
        val scvhApplied = trySetScvhAlpha(1f)
        val coverApplied = if (!scvhApplied) trySetSurfaceAlphaFast(1f) else false
        Log.i(TAG, "broadcast: fast scvh=$scvhApplied refl=$coverApplied dt=${SystemClock.uptimeMillis() - recvAt}ms")

        // Arm the synchronous-onPause path *before* hopping to main.
        // If the activity pauses before the queued post is drained,
        // `onActivityPaused` reads this flag and runs the mount
        // synchronously. A plain @Volatile write is enough.
        pendingUserLeaveMount = true

        // Still hop to main: even with the fast SC path winning the
        // pixel race, we need to (a) set view.alpha=1 so the View
        // tree matches the SC and a later traversal doesn't undo it,
        // (b) clear FLAG_NOT_TOUCHABLE / FLAG_ALT_FOCUSABLE_IM, and
        // (c) refresh blur capture if active. postAtFrontOfQueue
        // skips queued messages but can't preempt the one currently
        // running.
        mainHandler.postAtFrontOfQueue {
          if (!isEnabled) {
            pendingUserLeaveMount = false
            return@postAtFrontOfQueue
          }
          if (!pendingUserLeaveMount || isVisible) {
            Log.i(TAG, "broadcast post: no-op (pending=$pendingUserLeaveMount isVisible=$isVisible)")
            return@postAtFrontOfQueue
          }
          Log.i(TAG, "broadcast post: mounting (lag=${SystemClock.uptimeMillis() - recvAt}ms)")
          performUserLeaveMount()
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
