# Changelog

## 0.1.7 - 2026-06-01

-   Android: fix the cover never appearing on Samsung One UI (observed on Samsung Galaxy A05 / SM-A057F, Android 14). The host-window walk in `ensureCoverOnTopmost` and `addCover` excluded the cover Window's root (`coverView`, a `SurfaceView` on the SCVH path) but not the SCVH-hosted content (`coverContent`, a `FreezableFrameLayout`). On Samsung One UI's customised WindowManager the SCVH content appears in `WindowManagerGlobal.mViews` despite living inside the SCVH's own `ViewRootImpl`, so the walk identified our own content view as the topmost host, tried to attach the cover as a sub-window of itself, and `WindowManager.addView` threw `BadTokenException: Unable to add window — token … is not valid; is your activity running?`. The cover never reached the Recents thumbnail on those devices. Fix: pass `exclude2 = coverContent` to `CoverWindowAttachment.topmostHostViewFor` at all three callsites so the walk skips both views and falls back to the activity decor. `CoverWindowAttachment` already supported the parameter (the blur source picker uses it for the same reason). Verified on Samsung Galaxy A05 (Android 14, One UI) — Recents thumbnail now shows the configured `#5F8AFA` background with the splash icon, exactly the iOS App Switcher parity it was designed for.

`minSdkVersion=23`, public API, and iOS behavior unchanged.

## 0.1.6 - 2026-06-01

-   Android: eliminate the `IllegalArgumentException: Invalid window token (never added or removed already)` crash from `WindowlessWindowManager.relayout` reported on production devices (NEAR Mobile Play Console and others). v0.1.5's reflective traversal cancel only works on Android versions that don't block the hidden-API probe; Android 14+ hidden-API enforcement leaves the crash unfixed there. Five new layered defences in `detachCoverView` close the race deterministically without sacrificing the snapshot-race fix:
    -   `FreezableFrameLayout` cover-content root whose `requestLayout()` / `invalidate()` no-op while a `frozen` flag is set. Set first thing in `detachCoverView`; from that point on no `requestLayout` reaches `ViewRootImpl.scheduleTraversals`, so no new `TraversalRunnable` can be queued during teardown.
    -   Snapshot-then-null-out of shared state plus a `coverDetaching` re-entrance guard. Any synchronous re-entrant call (animation cancel listener, `dispatchDetachedFromWindow`) sees cleared fields and bails before double-removing or double-releasing.
    -   `WindowManager.removeViewImmediate` so `dispatchDetachedFromWindow` runs inline while the freeze is active; falls back to async `removeView` on OEM impls that reject immediate removal in transitional states.
    -   `setCoverVisibility` validity-checks `view.windowToken != null` before `updateViewLayout` — same WMS/WWM path that throws when the token is gone.
    -   `deferredReleaseScvh` schedules `safeReleaseScvh` via `Choreographer.postFrameCallback` (animation phase) → nested `mainHandler.post`, so it runs strictly AFTER the next frame's traversal callbacks. Any `TraversalRunnable` already in the queue (including ones reflection couldn't cancel) fires with the WWM token still valid; release happens after, removing the token only when nothing is left to relayout. `ViewRootImpl.mTraversalScheduled` guarantees at most one queued runnable per ViewRootImpl, so one frame's wait drains it. Wired into all six release call sites — five SCVH attach recovery branches in `tryAttachCoverViaScvh` plus the teardown in `detachCoverView`.
-   Android: skip SCVH entirely on Android 11 (API 30) when `INTERNAL_SYSTEM_WINDOW` is denied (signature-level permission no normal app holds). On this AOSP release the SCVH path through `WindowManagerService.addWindow` enforces this permission and `host.setView` throws `SecurityException` AFTER `ViewRootImpl.setView` has already called `requestLayout()` — queuing a `TraversalRunnable` that fires the same vsync (TRAVERSAL phase, before any deferred work) on a token that was never registered with the WWM, throwing the "never added" variant of `Invalid window token` from `Looper.loop`. The deferred release can't help in this case (the runnable fires in the same vsync's traversal phase, before our queued Handler message); the only safe path is to never call `setView` on these devices. Scoped to API 30 only — Android 12+ dropped the permission check for SCVH's `addToDisplay` path, so SCVH and the SurfaceFlinger-direct alpha toggle continue to work unmodified on every modern device.
-   Android: real frosted-glass blur on Android 11 (API 30) instead of a flat tint. `RenderEffect.createBlurEffect` requires API >= 31, and the previous fallback dropped the captured bitmap and painted a flat ~80% white tint, leaving underlying app content fully readable through the cover — broken privacy on every Android 11 host. The fallback now reuses the captured bitmap at 1/12 in each dimension (1/144 pixels) and lets `ImageView`'s bilinear filter smudge it back to display size on draw, layered with the style tint as foreground exactly like the API >= S path. The visual contract matches across versions and the cover obscures content to a similar degree as iOS's `UIBlurEffect`.

`minSdkVersion=23`, public API, and iOS behavior unchanged.

## 0.1.5 - 2026-05-22

-   Android: cancel pending Choreographer traversals on the SCVH's internal `ViewRootImpl` before every release. Without this, a `TraversalRunnable` queued by the partial `setView` could fire after the windowless window token was removed, throwing `IllegalArgumentException: Invalid window token (never added or removed already)` from `WindowlessWindowManager.relayout` on a later vsync, outside any try/catch of ours because the runnable is dispatched from `Looper.loop`. Implemented as a reflective call to `ViewRootImpl.unscheduleTraversals()` via a field probe (the SCVH field name has not been stable across Android versions); on reflection failure the helper latches off so subsequent calls don't keep retrying.
-   Android: latch SCVH as "known-bad" for the rest of the process on the first failure of any step in `tryAttachCoverViaScvh` (ctor, setView, null surfacePackage, initial setAlpha, setChildSurfacePackage, addView). Belt-and-suspenders alongside the unschedule helper: if the reflective traversal cancel silently no-ops on a future Android version, the latch caps the orphaned-traversal blast radius to exactly one queued crash per process — subsequent attempts go straight to the legacy non-SCVH attach path.

## 0.1.4 - 2026-05-22

-   Android: fix process crash in the SCVH attach recovery path. When `SurfaceControlViewHost.setView()` (or one of the follow-up steps in `tryAttachCoverViaScvh`) failed, the recovery `host.release()` propagated a framework NPE. `ViewRootImpl$InputStage.onDetachedFromWindow()` on a null head, because the SCVH's input stage chain hadn't been wired up yet. All five recovery `release()` calls plus the teardown release in `detachCoverView` now go through a `safeReleaseScvh` helper that swallows the framework exception, so the legacy non-SCVH attach path actually runs as the intended fallback. Recovery log lines also now include the exception class name.

## 0.1.3 - 2026-05-13

-   Android: fix the Recents thumbnail race that intermittently leaked the underlying app content — especially under load or with a `<Modal>` open. On API 30+, the cover content is now hosted in a `SurfaceControlViewHost` and visibility is toggled via `SurfaceControl.Transaction.setAlpha` straight from the broadcast HandlerThread, bypassing the View → `ViewRootImpl` → compose chain that was losing the race. Closes [#1](https://github.com/amillez/react-native-cover/issues/1).
-   Android: fix the cover missing on the first Home press while a `<Modal>` is open. The pre-mounted cover is now re-parented to the modal's window the moment it opens (via an `OnWindowFocusChangeListener` on the activity decor), so it's always composed above the modal. Out-of-process focus changes (permission prompts, biometrics, notification shade) still don't trigger a re-parent.
-   Android: fix blur mode showing only a translucent tint on the new SCVH path — the blur-source picker now skips the cover's wrapping `SurfaceView` (which software-draws as transparent) and falls back to the activity decor.
-   Android: ask the OS for the display's highest refresh rate while the cover is attached, shortening the snapshot race window on 90/120 Hz panels.

`minSdkVersion=23`, public API, and iOS behavior unchanged.

## 0.1.2 - 2026-05-07

-   Android: fix soft keyboard input being swallowed by the pre-mounted cover panel. The invisible panel now sets `FLAG_ALT_FOCUSABLE_IM` alongside `FLAG_NOT_TOUCHABLE` so the IME sits above it and keystrokes reach the focused `TextInput`; both flags are cleared again when the cover becomes visible so it still paints over any lingering keyboard.

## 0.1.1 - 2026-05-07

Initial release.

-   Native privacy cover for React Native built on Nitro Modules.
-   iOS: hides app content behind an overlay in the App Switcher.
-   Android: hides app content in the Recents screen.
