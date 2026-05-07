import type { HybridObject } from "react-native-nitro-modules";

/**
 * How the image is scaled to fit the cover.
 * - `cover` — fill, preserving aspect ratio (may crop).
 * - `contain` — fit entirely, preserving aspect ratio (may letterbox).
 * - `stretch` — fill, ignoring aspect ratio.
 * - `center` — original size, no scaling.
 */
export type CoverResizeMode = "cover" | "contain" | "stretch" | "center";

/**
 * Visual style of the blur effect.
 * - iOS maps to `UIBlurEffect.Style`: `light → .light`, `dark → .dark`,
 *   `extraLight → .extraLight`, `regular → .regular`.
 * - Android (API 31+) uses `RenderEffect.createBlurEffect` over a snapshot
 *   of the activity, with a tinted overlay matching the style. Below API
 *   31 it falls back to a translucent color of the same tone.
 */
export type CoverBlurStyle = "light" | "dark" | "regular" | "extraLight";

/**
 * Easing curves for the cover fade animation.
 */
export type CoverEasing = "linear" | "easeIn" | "easeOut" | "easeInOut";

/** Horizontal anchor for positioning the image inside the cover. */
export type CoverPositionX = "left" | "center" | "right";

/** Vertical anchor for positioning the image inside the cover. */
export type CoverPositionY = "top" | "center" | "bottom";

/**
 * Options for the cover's image overlay. All fields except `uri` are
 * optional; the defaults render the image filling the cover, centered.
 */
export interface CoverImageOptions {
  /**
   * Source URI. May be `file://`, `http(s)://`, or `data:`. To use a
   * `require()`-bundled asset, resolve it with
   * `Image.resolveAssetSource(require('./foo.png')).uri`. On Android,
   * `file:///android_asset/<path>` and bare resource names from the
   * RN asset registry are also supported.
   */
  uri: string;
  /**
   * How the image is scaled. Default `'contain'`.
   */
  resizeMode?: CoverResizeMode;
  /**
   * Horizontal anchor. Default `'center'`. Visible only when the image
   * doesn't fill the cover horizontally (`resizeMode: 'contain' | 'center'`
   * and/or `width > 0`).
   */
  x?: CoverPositionX;
  /**
   * Vertical anchor. Default `'center'`. Visible only when the image
   * doesn't fill the cover vertically (`resizeMode: 'contain' | 'center'`
   * and/or `height > 0`).
   */
  y?: CoverPositionY;
  /**
   * Width of the box the image is laid out into, in DIPs / points.
   * `0` (or omitted) = use the full cover width on this axis.
   */
  width?: number;
  /**
   * Height of the box the image is laid out into, in DIPs / points.
   * `0` (or omitted) = use the full cover height on this axis.
   */
  height?: number;
}

/**
 * Native privacy cover. When enabled, a view is placed in the app's window
 * the moment the OS notifies the app it is leaving the foreground, so the
 * App Switcher (iOS) / Recents screen (Android) thumbnail does not expose
 * sensitive in-app content. The view is removed when the app is foregrounded
 * again.
 *
 * The cover supports four content combinations:
 *
 * - **Color** — `setColor(hex)` and no image set
 * - **Image** — `setColor("#00000000")` (or any transparent hex) plus
 *   `setImage({ uri, ... })`
 * - **Image + color** — both `setColor(hex)` and `setImage({ uri, ... })`
 *   set; the color paints the background and the image is positioned on top
 * - **Blur** — `setBlur(style, intensity?)`; mutually exclusive with color
 *   and image
 *
 * The defaults until any setter is called are: opaque black, no image, no
 * blur. The four mode setters are mutually exclusive — each one resets
 * the state owned by the others:
 *
 * - `setColor` / `setImage` / `clearImage` exit blur mode.
 * - `setBlur` resets the background color to opaque black and clears the
 *   image overlay.
 *
 * Setters are intended to be called rarely (typically once at startup).
 * They dispatch UI work to the main thread asynchronously, so they never
 * block the JS thread, but they are not optimized for per-render or
 * per-frame use.
 */
export interface Cover
  extends HybridObject<{
    ios: "swift";
    android: "kotlin";
  }> {
  /**
   * Whether the cover is currently armed. When `true`, the cover view is
   * mounted on background and removed on foreground. When `false`, no
   * lifecycle listeners are attached.
   */
  readonly isEnabled: boolean;

  /**
   * Whether the cover is currently visible on screen — i.e. the cover
   * view exists in the window hierarchy AND is rendered with `alpha = 1`.
   *
   * Becomes `true` synchronously when the cover is mounted (the
   * background-mount path, manual `show()`, or a mode change while
   * already mounted).
   *
   * Becomes `false` only **after** any fade-out animation completes, so
   * that callers can use `isVisible === false` as a "the cover is
   * fully gone" signal — useful for tests that poll on it.
   *
   * Note that on Android the cover panel may remain *attached* (alpha=0,
   * `FLAG_NOT_TOUCHABLE` on) while the module is enabled, so this property
   * tracks logical visibility, not raw window attachment.
   */
  readonly isVisible: boolean;

  /**
   * Arm the cover. Idempotent — calling twice has no effect.
   */
  enable(): void;

  /**
   * Disarm the cover and remove the view if it is currently mounted.
   * Idempotent.
   */
  disable(): void;

  /**
   * Set the background color of the cover. Accepts `#RGB`, `#RGBA`,
   * `#RRGGBB`, or `#RRGGBBAA` hex strings — pass `#00000000` (or any
   * zero-alpha hex) for a transparent background, useful when only an
   * image should be visible. Calling this while in blur mode exits blur
   * mode.
   *
   * Defaults to opaque black if never called. Invalid hex strings fall
   * back to opaque black.
   */
  setColor(hex: string): void;

  /**
   * Set an image overlay on top of the background color. See
   * `CoverImageOptions` for field semantics; only `uri` is required.
   *
   * The image is decoded off the main thread and held in memory so that
   * subsequent background-mounts paint it synchronously. Calling
   * `setImage` again with a different URI cancels any in-flight HTTP
   * fetch, drops the previous bitmap, and starts a new decode; passing
   * the same URI reuses the existing bitmap and only updates the layout
   * fields.
   *
   * Calling this while in blur mode exits blur mode.
   */
  setImage(options: CoverImageOptions): void;

  /**
   * Remove the image overlay. The background color is preserved. Calling
   * this while in blur mode exits blur mode.
   */
  clearImage(): void;

  /**
   * Switch the cover to blur mode. Resets the background color to opaque
   * black and clears the image overlay — blur is mutually exclusive with
   * the color/image modes.
   *
   * `intensity` is a number in `[0, 1]` controlling how strong the blur
   * looks. `0` = no blur (visually transparent), `1` = the full
   * `UIBlurEffect.Style` strength on iOS or the maximum bitmap blur
   * radius on Android. Defaults to `0.4` — heavy enough to obscure
   * sensitive details, light enough to read as frosted glass rather than
   * a flat wash.
   */
  setBlur(style: CoverBlurStyle, intensity?: number): void;

  /**
   * Configure the fade animation used when the cover mounts and unmounts.
   * Pass `durationMs: 0` to disable the animation. `easing` defaults to
   * `easeInOut` when omitted. Applies to subsequent lifecycle-driven
   * shows/hides and manual `show()` / `hide()` calls. The fade is always
   * skipped on the background-mount because the OS captures the App
   * Switcher snapshot before the animation could finish.
   */
  setFade(durationMs: number, easing?: CoverEasing): void;

  /**
   * Force the cover to mount immediately (regardless of the current app
   * state). Primarily useful for e2e tests — production code should rely
   * on the automatic background lifecycle.
   */
  show(): void;

  /**
   * Force the cover to unmount immediately. Counterpart to `show()`.
   */
  hide(): void;
}
