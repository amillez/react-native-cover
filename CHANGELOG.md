# Changelog

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
