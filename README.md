# react-native-cover

Native privacy cover for React Native. Hides your app behind an opaque view in the iOS App Switcher and Android Recents screen.

<img width="200" height="435" alt="cover-android-demo" src="https://github.com/user-attachments/assets/48e911a9-eba3-49be-a69e-21253493a244" />
<img width="200" height="435" alt="cover-ios-demo" src="https://github.com/user-attachments/assets/1ccf4c5e-671c-41a6-b6f8-21b0e50b0b33" />

Built with [Nitro Modules](https://nitro.margelo.com/).

## Features

-   **Native lifecycle hooks** — mounts the cover synchronously from `applicationDidEnterBackground` (iOS) and `ACTION_CLOSE_SYSTEM_DIALOGS` (Android), before the OS captures the App Switcher / Recents thumbnail.
-   **Four content modes** — solid color, image, image-on-color, or blur. Modes are mutually exclusive and switch in place.
-   **Configurable image overlay** — `resizeMode`, anchor (`x` / `y`), and explicit `width` / `height` for badge-style placement. Decoded off the main thread; bitmap is held so re-mounts paint instantly.
-   **Image source flexibility** — `file://`, `http(s)://`, `data:` URLs, and React Native bundled assets via `Image.resolveAssetSource`.
-   **Tunable blur** — `light` / `dark` / `regular` / `extraLight` styles with a `[0, 1]` intensity that updates in place.
-   **Fade animation** — configurable duration and easing for foreground unmount and manual `show()` / `hide()`; skipped on background mount where there is no time before the OS snapshot.
-   **Above-modal rendering** — paints above React Native `<Modal>` on both platforms (dedicated `UIWindow` above `.alert` on iOS; `TYPE_APPLICATION_PANEL` window on Android).
-   **Multi-scene support** — every attached scene gets its own cover window (iPad split view, Stage Manager, visionOS).
-   **New and Old Architecture** — both supported via `react-native-nitro-modules`.

## Why this exists

Hiding sensitive content from the App Switcher / Recents thumbnail _cannot_ be done from JavaScript reliably:

-   **iOS** captures the App Switcher snapshot **shortly after `applicationDidEnterBackground:` returns**. By then the React Native JS thread has been suspended — any `AppState.addEventListener('change', …)` callback that tries to mount an overlay view is racing the snapshot and almost always loses.
-   **`AppState === 'inactive'`** also fires for system permission dialogs, Face ID / Touch ID prompts, Control Center, and the Apple Pay sheet. So you can't just react to `inactive` either — your "privacy cover" would flash in the middle of a real authentication flow.
-   **Android** captures the Recents thumbnail shortly after `Activity.onPause`, while the JS bridge is winding down — same shape of problem. Reacting to `onPause` directly is also wrong, for the same reason `inactive` is wrong on iOS: it fires for permission dialogs and biometric prompts.

The only correct approach is to install the cover view **natively**, synchronously, from `applicationDidEnterBackground` (iOS) and the `ACTION_CLOSE_SYSTEM_DIALOGS` system broadcast on Android — both of which fire only for real backgrounding, before the OS captures the thumbnail. That is exactly what this module does.

## Installation

```bash
npm install react-native-cover react-native-nitro-modules
```

CocoaPods (iOS):

```bash
cd ios && pod install
```

For Expo apps, run a prebuild after installing:

```bash
npx expo prebuild --clean
```

The module ships a [`react-native-nitro-modules`](https://nitro.margelo.com/) HybridObject. Both the New Architecture and the Old Architecture are supported.

## Usage

```tsx
import { Image } from "react-native";
import { Cover } from "react-native-cover";

// Arm the cover. Once enabled, the cover is mounted natively when the
// app goes to background and removed when it comes back to foreground.
Cover.enable();

// Background color — opaque black by default. Use #00000000 for a
// transparent background under an image-only cover. Accepts #RGB,
// #RRGGBB, and #RRGGBBAA.
Cover.setColor("#1E1B4B");

// Image overlay (rendered on top of the background color). All fields
// except `uri` are optional and default to filling the cover with
// `contain` / `center` / `center`. `width` and `height` are in DIPs
// (points); 0 (or omitted) means "fill the cover on that axis". The
// image is decoded off the main thread and the bitmap is held so
// subsequent background-mounts paint instantly.
const asset = Image.resolveAssetSource(require("./privacy-logo.png"));
Cover.setImage({ uri: asset.uri, resizeMode: "contain", y: "bottom" });

// Blur — replaces color + image visually. `setBlur` clears the color
// (back to opaque black) and the image overlay; switching back via
// `setColor` / `setImage` starts from a clean slate. `intensity`
// defaults to 0.4 (soft frosted glass); 1.0 is the full UIBlurEffect /
// max GPU blur radius. Re-issuing `setBlur` with the same `style` but a
// different `intensity` updates in place — fast and animatable.
Cover.setBlur("dark");
Cover.setBlur("dark", 1.0);

// Remove the image overlay (and exit blur mode). The background color
// is preserved.
Cover.clearImage();

// Fade animation for foreground unmount and manual show/hide. Skipped
// on the background-mount path because the OS snapshots the App Switcher
// before any animation could finish. `easing` defaults to `easeInOut`.
Cover.setFade(200);
Cover.setFade(600, "easeIn");

Cover.disable();
Cover.isEnabled;
Cover.isVisible;
Cover.show();
Cover.hide();
```

### Content combinations

The cover has four visual combinations:

| Combination       | How to get it                                                                           |
| ----------------- | --------------------------------------------------------------------------------------- |
| **Color only**    | `setColor(hex)` — image cleared (or never set)                                          |
| **Image only**    | `setColor("#00000000")` + `setImage({ uri })` — transparent background under the image  |
| **Image + color** | `setColor(hex)` + `setImage({ uri, … })` — color paints behind, image positioned on top |
| **Blur**          | `setBlur(style)` — replaces color + image visually and clears their state               |

Defaults until any setter is called: opaque black, no image, no blur.

The mode setters are mutually exclusive — each one resets the state owned by the others:

-   `setColor`, `setImage`, and `clearImage` exit blur mode.
-   `setBlur` resets the background color to opaque black and clears the image overlay.

So a `setBlur` followed later by `setColor` returns to a flat color cover with **no** image — there is no implicit "restore the pre-blur cover" behavior.

> Setters are intended to be called rarely (typically once).
> They dispatch all UI work to the main thread asynchronously, so they
> never block the JS thread, but they are not optimized for per-render
> or per-frame use.

### `setBlur(style, intensity?)`

| Param       | Type / values                                    | Notes                                                                                                                                                                                                                                                                              |
| ----------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `style`     | `'light' \| 'dark' \| 'regular' \| 'extraLight'` | iOS maps directly to `UIBlurEffect.Style`.                                                                                                                                                                                                                                         |
| `intensity` | `number` in `[0, 1]`, default `0.4`              | iOS uses a `UIViewPropertyAnimator` paused at `fractionComplete = intensity` so any value between 0 and 1 is allowed without dipping into private API. Android scales the `RenderEffect` blur radius linearly (max 50 px); `0` removes the blur effect entirely on both platforms. |

Calling `setBlur` repeatedly with the same `style` but a different `intensity` is fast — the existing visual-effect view is updated in place rather than rebuilt.

### `setImage(options)`

```ts
Cover.setImage({
    uri: "file:///…/logo.png",
    resizeMode: "contain", // optional, default 'contain'
    x: "center", // optional, default 'center'
    y: "bottom", // optional, default 'center'
    width: 120, // optional, default 0 = fill cover width
    height: 120, // optional, default 0 = fill cover height
});
```

| Field        | Type / values                                   | Default     | Notes                                                                        |
| ------------ | ----------------------------------------------- | ----------- | ---------------------------------------------------------------------------- |
| `uri`        | `string` (required)                             | —           | `file://`, `http(s)://`, `data:`, RN bundled-asset URI. See "Image source".  |
| `resizeMode` | `'cover' \| 'contain' \| 'stretch' \| 'center'` | `'contain'` | Maps to UIKit `contentMode` and Android `ImageView.ScaleType`.               |
| `x`          | `'left' \| 'center' \| 'right'`                 | `'center'`  | Horizontal anchor of the image inside its sized box.                         |
| `y`          | `'top' \| 'center' \| 'bottom'`                 | `'center'`  | Vertical anchor of the image inside its sized box.                           |
| `width`      | `number` (points / DIPs)                        | `0`         | `0` = full cover width. Otherwise the image is laid out into a fixed column. |
| `height`     | `number` (points / DIPs)                        | `0`         | `0` = full cover height. Otherwise the image is laid out into a fixed row.   |

For `stretch` and `cover` the image fills both dimensions of its box, so `x` / `y` have no visible effect. For `contain` and `center` the anchor controls where the image sits inside the available space. Combined with non-zero `width` / `height` you get badge-style placement (e.g. a 120×120 logo anchored to the bottom-right corner of the cover).

### Image source

```ts
import { Image } from "react-native";
const asset = Image.resolveAssetSource(require("./privacy-logo.png"));
Cover.setImage({ uri: asset.uri });
```

The native side decodes:

-   `file://` and bare paths via `UIImage(contentsOfFile:)` / `BitmapFactory.decodeFile`
-   `http://` and `https://` via `URLSession` (iOS) / `HttpURLConnection` (Android), each with a 10-second timeout
-   `data:` URLs, both base64 and percent-encoded
-   On Android, also `file:///android_asset/<path>` and bare resource names from `Image.resolveAssetSource(require(...))` in release builds (resolved via `AssetManager` / `Resources`)

The decoded bitmap is held alongside the current image config. Calling `setImage` again with a different URI starts a fresh decode (and cancels any in-flight HTTP fetch from the previous URI); passing the same URI reuses the existing bitmap and only updates the layout fields.

### Fade animation

`setFade(durationMs, easing?)` configures the fade for **all subsequent**
mounts and unmounts — both the lifecycle-driven ones and manual `show()`
/ `hide()`. The fade is **skipped on background mount** because there
is no time before the OS captures the App Switcher snapshot — the cover
needs to be opaque immediately. Fade applies to the foreground unmount
and to manual show/hide.

`easing` defaults to `easeInOut`. Default duration: `0ms` (no animation).

### TypeScript

```ts
import type { CoverModule } from "react-native-cover";
```

## How it works

### iOS (`HybridCover.swift`)

-   On `enable()`, registers two observers on `NotificationCenter.default`:
    -   `UIApplication.didEnterBackgroundNotification` → for every attached scene, creates a dedicated cover-only `UIWindow` above the alert level and mounts a `UIView` container in its rootViewController
    -   `UIApplication.willEnterForegroundNotification` → fades the containers out, hides each cover window, and detaches it from its scene so the window deallocates
-   The cover lives in its own window (rather than as a subview of the host's `UIWindow`) at a `windowLevel` above `.alert` so it sits unconditionally on top of every other window in the scene — host content, RN `<Modal>`, status bar, and system alerts. The cover only attaches in `applicationDidEnterBackground`, which does not fire while system permission dialogs / Face ID / Apple Pay are presented (those drive `applicationWillResignActive` instead), so the high level does not interfere with those flows in normal use.
-   The cover window is reused across cycles (created once on `enable()` and toggled via `isHidden`) so the App Switcher snapshot finds an already-laid-out surface, and intermediate `setColor` / `setImage` / `setBlur` calls only mutate the existing view tree.
-   The view tree is rebuilt from the current `(color, image?, blur?)` state whenever the mode changes:
    -   `blur` set → container hosts a `UIVisualEffectView` filling its bounds; color/image ignored visually.
    -   otherwise → container is painted with `backgroundColor`; if an image is set, a `UIImageView` is added with a frame computed from `width` / `height` and anchored per `x` / `y`.
-   Images are decoded off the main thread and the bitmap is held alongside the current image config, so the cover re-mounts synchronously without waiting for I/O. In-flight HTTP fetches are cancelled when `setImage` is called again with a different URI.
-   All JS-thread setters dispatch the actual UI mutation to the main thread asynchronously, so the JS thread is never blocked on the run loop.
-   Multi-scene (iPad split view, Stage Manager, visionOS) is supported: every attached scene gets its own cover window.

### Android (`HybridCover.kt`)

-   On `enable()`, registers an `Application.ActivityLifecycleCallbacks` and a runtime `BroadcastReceiver` for `Intent.ACTION_CLOSE_SYSTEM_DIALOGS`:
    -   Broadcast received with `reason ∈ { "homekey", "recentapps", "assist" }` → mounts a `FrameLayout` container as its own window via `WindowManager.addView` with `TYPE_APPLICATION_PANEL`
    -   `onActivityStopped` (backup signal for devices with flaky broadcast delivery) → mounts the cover too
    -   The topmost-host window's focus regain (observed via `ViewTreeObserver.OnWindowFocusChangeListener`) → fade the container out and detach
-   The cover is added as a separate window (rather than as a child of the activity's `decorView`) so it sits **above** any React Native `Modal` — RN Modals are Dialogs at z-layer 2, while `TYPE_APPLICATION_PANEL` is at z-layer 1000. The window is `FLAG_NOT_FOCUSABLE` so it doesn't steal input focus from a Modal underneath; it still consumes touches via the cover's own `OnTouchListener`.
-   The cover is **pre-mounted** invisible (alpha=0 + `FLAG_NOT_TOUCHABLE`) the moment it's enabled. The home/recents broadcast then becomes a fast property toggle — no `addView`, no first-frame race against the OS thumbnail capture.
-   The container is rebuilt from the current `(color, image?, blur?)` state every time it mounts:
    -   `blur` set → an `ImageView` filled with a 1/4-scale snapshot of the topmost host window (so a foreground Modal's content is also obscured), blurred via `RenderEffect.createBlurEffect` (API 31+) or degraded to a tinted translucent background on older Android.
    -   otherwise → container's background is the color; if an image is set, an `ImageView` is added as a child with a `LayoutParams(w, h, gravity)` computed from `width` / `height` (DIP-converted) and `x` / `y`.
-   Images are decoded off the main thread by a single-thread executor and the bitmap is held alongside the current image config; in-flight HTTP fetches are cancelled when `setImage` is called again with a different URI.
-   All JS-thread setters dispatch through the main `Handler`, so the JS thread is never blocked.
-   The container's `tag` and `contentDescription` are both `"CoverOverlay"` / `"Privacy cover"` so it can be located by accessibility tooling and e2e tests.

### Why not iOS `willResignActive` / Android `onPause`?

Both fire for **every** loss of focus, not just real backgrounding: permission dialogs, biometric prompts, incoming calls, system control panels, the notification shade. Reacting to them would cause the cover to flash on top of system UI and break real authentication flows.

iOS gives us a clean discriminator out of the box: `applicationDidEnterBackground` fires only when the app actually leaves the foreground.

Android does not have a direct equivalent in `Application.ActivityLifecycleCallbacks` — the closest, `onActivityStopped`, runs too late (the OS captures the Recents thumbnail before it). What it does have is the protected system broadcast `ACTION_CLOSE_SYSTEM_DIALOGS`, which `PhoneWindowManager` dispatches just before pausing the foreground activity for a user-initiated app leave (Home key, Recents key, Assist key, or the equivalent gestures), and **not** for transient focus loss. We listen to that broadcast and mount the cover synchronously when the `reason` extra indicates a real leave.

### Android: known limitations

-   The `reason` extras on `ACTION_CLOSE_SYSTEM_DIALOGS` come from `PhoneWindowManager` and have been stable across AOSP releases, but they are not part of the public Android SDK contract. An OEM that ships a custom variant of `PhoneWindowManager` could in principle omit or rename them. We have not seen this in practice on shipping devices.
-   Topmost-window discovery (used to attach the cover above an open `<Modal>` Dialog and to capture the right surface for blur) reads the per-process `WindowManagerGlobal.mViews` list via reflection. The greylist allows this on every shipping Android version we test, but it could be tightened in a future release. We fall back to the activity's own decor view if the reflection fails.
-   Like iOS's `didEnterBackground`, this signal does not fire for non-user-initiated background transitions: incoming phone calls, foreground services taking over the screen, programmatic `startActivity` to another app. The cover will not appear in the Recents thumbnail in those cases. This matches the iOS behavior — a deliberate trade-off versus the modal-flash that reacting to every focus loss would cause.

## Contributing

See the [contributing guide](./CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## License

MIT — see [LICENSE](./LICENSE).
