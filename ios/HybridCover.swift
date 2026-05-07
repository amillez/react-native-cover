//
//  HybridCover.swift
//  react-native-cover
//

import Foundation
import UIKit
import NitroModules

/// One mounted cover instance. Multi-scene apps (iPad split view, Stage
/// Manager, visionOS) can have several at once, one per active scene.
///
/// We own the window — it's a dedicated cover-only UIWindow at a
/// `windowLevel` above `.alert` so the cover sits unconditionally on top
/// of every other window in the scene (host content, RN Modals, status
/// bar, system alerts). Held strong because dropping our reference would
/// deallocate the window and tear the cover down before the OS captures
/// the App Switcher snapshot.
private struct MountedCover {
  let window: UIWindow
  let view: UIView
  /// Held strong because UIBlurEffect partial-intensity needs the
  /// animator to outlive the visual-effect view; nil in color/image
  /// mode.
  var blurAnimator: UIViewPropertyAnimator?
}

private struct ImageConfig {
  let uri: String
  let resizeMode: CoverResizeMode
  let x: CoverPositionX
  let y: CoverPositionY
  let width: CGFloat   // 0 = use container width
  let height: CGFloat  // 0 = use container height
  var image: UIImage?
}

/// Default blur intensity when the JS caller passes `undefined` to
/// `setBlur(style)`. Calibrated to be heavy enough to obscure sensitive
/// details, light enough to read as frosted glass rather than a flat
/// wash.
private let defaultBlurIntensity: CGFloat = 0.4

final class HybridCover: HybridCoverSpec {
  // MARK: - JS-thread-readable atomic state

  private let stateLock = NSLock()
  private var _isEnabled: Bool = false
  private var _isVisible: Bool = false

  var isEnabled: Bool {
    stateLock.lock(); defer { stateLock.unlock() }
    return _isEnabled
  }
  var isVisible: Bool {
    stateLock.lock(); defer { stateLock.unlock() }
    return _isVisible
  }

  private func writeIsEnabled(_ value: Bool) {
    stateLock.lock(); _isEnabled = value; stateLock.unlock()
  }
  private func writeIsVisible(_ value: Bool) {
    stateLock.lock(); _isVisible = value; stateLock.unlock()
  }

  // MARK: - Main-thread-owned state
  // All reads and writes happen on the main thread. JS-thread setters
  // dispatch to main async; getters above use the lock.

  private var backgroundColor: UIColor = .black
  private var imageConfig: ImageConfig?
  private var blurStyle: CoverBlurStyle?
  private var blurIntensity: CGFloat = defaultBlurIntensity

  private var fadeDuration: TimeInterval = 0.0
  private var fadeEasing: UIView.AnimationOptions = .curveEaseInOut

  /// Monotonic counter incremented on every show/hide transition.
  /// Captured by each animation closure and re-checked in its
  /// completion so a faded-out hide can't clobber a subsequent show
  /// (or vice versa) when transitions overlap.
  private var transitionToken: UInt64 = 0

  /// Cached cover windows, keyed by scene. Created on `enable()` (and on
  /// scene activation) and kept across cycles — the App Switcher snapshot
  /// finds an already-laid-out surface so mounting reduces to a single
  /// `isHidden = false`. Removed only on `disable()` or when the
  /// underlying scene detaches.
  private var mounted: [MountedCover] = []

  private let imageFetcher = CoverImageFetcher()

  deinit {
    NotificationCenter.default.removeObserver(self)
  }

  // MARK: - Lifecycle

  func enable() throws {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      guard !self.isEnabled else { return }
      self.writeIsEnabled(true)

      let center = NotificationCenter.default
      // Per-scene notifications: in iPad split view / Stage Manager /
      // visionOS one window can background while others stay foreground,
      // and the application-level notifications don't fire for that.
      // The scene-level notifications post once per scene and carry the
      // scene as `object`, so we can mount/unmount only the affected
      // scene's cover.
      center.addObserver(
        self,
        selector: #selector(self.handleSceneBackground(_:)),
        name: UIScene.didEnterBackgroundNotification,
        object: nil
      )
      center.addObserver(
        self,
        selector: #selector(self.handleSceneForeground(_:)),
        name: UIScene.willEnterForegroundNotification,
        object: nil
      )
      // Pre-mount cover windows for every currently-active scene so the
      // first didEnterBackground fires a fast `isHidden = false` instead
      // of a fresh allocation.
      self.ensureCoversForActiveScenes(visible: false)
    }
  }

  func disable() throws {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      guard self.isEnabled else { return }
      self.writeIsEnabled(false)
      NotificationCenter.default.removeObserver(self)
      self.tearDownAllCovers()
    }
  }

  // MARK: - Mode setters

  func setColor(hex: String) throws {
    let color = UIColor(coverHex: hex) ?? .black
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.backgroundColor = color
      self.blurStyle = nil
      self.refreshCoverContent()
    }
  }

  func setImage(options: CoverImageOptions) throws {
    let uri = options.uri
    let resizeMode = options.resizeMode ?? .contain
    let x = options.x ?? .center
    let y = options.y ?? .center
    let width = max(0, options.width ?? 0)
    let height = max(0, options.height ?? 0)
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      // Reuse the already-decoded bitmap if the URI hasn't changed —
      // setImage is the natural place to re-issue layout fields. A
      // different URI invalidates any in-flight fetch and starts fresh.
      let existingImage: UIImage?
      if self.imageConfig?.uri == uri {
        existingImage = self.imageConfig?.image
      } else {
        existingImage = nil
        self.imageFetcher.cancelInflight()
      }
      let config = ImageConfig(
        uri: uri,
        resizeMode: resizeMode,
        x: x,
        y: y,
        width: CGFloat(width),
        height: CGFloat(height),
        image: existingImage
      )
      self.imageConfig = config
      self.blurStyle = nil
      self.refreshCoverContent()
      if existingImage == nil {
        self.imageFetcher.load(uri: uri) { [weak self] image in
          guard let self else { return }
          guard var current = self.imageConfig, current.uri == uri else { return }
          current.image = image
          self.imageConfig = current
          self.refreshCoverContent()
        }
      }
    }
  }

  func clearImage() throws {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.imageFetcher.cancelInflight()
      self.imageConfig = nil
      self.blurStyle = nil
      self.refreshCoverContent()
    }
  }

  func setBlur(style: CoverBlurStyle, intensity: Double?) throws {
    let raw = intensity ?? Double(defaultBlurIntensity)
    let clamped = CGFloat(max(0.0, min(1.0, raw)))
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      let prevStyle = self.blurStyle
      self.blurStyle = style
      self.blurIntensity = clamped
      // Blur is mutually exclusive with the color/image modes — reset
      // their state so a later setColor/setImage call starts from a
      // clean slate.
      self.backgroundColor = .black
      self.imageConfig = nil
      // Fast path: was already in blur mode with the same style and
      // only intensity changed → just retarget every mounted blur
      // animator. Skips rebuilding the visual-effect view.
      if prevStyle == style, !self.mounted.isEmpty,
         self.mounted.allSatisfy({ $0.blurAnimator != nil }) {
        for cover in self.mounted {
          cover.blurAnimator?.fractionComplete = clamped
        }
        return
      }
      self.refreshCoverContent()
    }
  }

  func setFade(durationMs: Double, easing: CoverEasing?) throws {
    let duration = max(0, durationMs / 1000.0)
    let option = (easing ?? .easeinout).uiKitOption
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.fadeDuration = duration
      self.fadeEasing = option
    }
  }

  // MARK: - Manual show/hide

  func show() throws {
    DispatchQueue.main.async { [weak self] in
      self?.showCovers(animated: true, scene: nil)
    }
  }

  func hide() throws {
    DispatchQueue.main.async { [weak self] in
      self?.hideCovers(animated: true, scene: nil)
    }
  }

  // MARK: - Notification handlers (main thread)

  @objc private func handleSceneBackground(_ note: Notification) {
    // The OS captures the App Switcher snapshot right after this
    // returns; the cover must already be opaque. No fade.
    let scene = note.object as? UIWindowScene
    showCovers(animated: false, scene: scene)
  }

  @objc private func handleSceneForeground(_ note: Notification) {
    let scene = note.object as? UIWindowScene
    hideCovers(animated: true, scene: scene)
  }

  // MARK: - View management (main thread)

  /// Make the cover visible. With `scene == nil`, targets every
  /// active scene (manual `show()` path). With a specific scene, only
  /// that scene's cover is shown — used by per-scene background
  /// notifications so a background event for one window doesn't
  /// trigger covers across other foreground windows in iPad split view
  /// / Stage Manager / visionOS.
  ///
  /// Reuses the pre-mounted windows from `enable()` when they exist —
  /// the surface is already laid out and drawn, so mounting reduces
  /// to toggling `isHidden`. Newly-activated scenes get a freshly-
  /// built window here.
  private func showCovers(animated: Bool, scene: UIWindowScene?) {
    ensureCoversForActiveScenes(visible: true)
    let targets = coversTargeting(scene: scene)
    guard !targets.isEmpty else { return }

    transitionToken &+= 1
    // Cancel any pending fade animations from a still-running hide so
    // its completion (which would re-hide the windows) sees a stale
    // token and bails.
    targets.forEach { $0.view.layer.removeAllAnimations() }

    let duration = animated ? fadeDuration : 0
    if duration > 0 {
      targets.forEach { $0.view.alpha = 0; $0.window.isHidden = false }
      writeIsVisible(true)
      UIView.animate(withDuration: duration, delay: 0, options: fadeEasing, animations: {
        targets.forEach { $0.view.alpha = 1 }
      })
    } else {
      targets.forEach { $0.view.alpha = 1; $0.window.isHidden = false }
      writeIsVisible(true)
    }
  }

  /// Fade out and hide cover windows. Windows stay attached to their
  /// scenes (cheap, ready for the next mount); they're only destroyed
  /// in `disable()`. With a specific scene, only that scene's cover
  /// is hidden — see `showCovers` for the multi-scene rationale.
  private func hideCovers(animated: Bool, scene: UIWindowScene?) {
    let targets = coversTargeting(scene: scene)
    guard !targets.isEmpty else {
      // No targets to hide. Only flip the global flag when this is an
      // all-scenes hide; per-scene hides shouldn't lie about the
      // remaining scenes.
      if scene == nil { writeIsVisible(false) }
      return
    }

    transitionToken &+= 1
    let token = transitionToken
    // Cancel any pending fade animations from a still-running show.
    targets.forEach { $0.view.layer.removeAllAnimations() }

    let duration = animated ? fadeDuration : 0
    if duration > 0 {
      UIView.animate(withDuration: duration, delay: 0, options: fadeEasing, animations: {
        targets.forEach { $0.view.alpha = 0 }
      }, completion: { [weak self] finished in
        guard let self else { return }
        // A newer transition bumped the token while we were animating —
        // let it own the final state.
        guard self.transitionToken == token else { return }
        guard finished else { return }
        for cover in targets { cover.window.isHidden = true }
        // `isVisible` reflects logical visibility — flip only after
        // every cover with alpha 0 is also hidden. With per-scene
        // hides, other scenes may still be showing the cover.
        self.recomputeIsVisible()
      })
    } else {
      targets.forEach { $0.view.alpha = 0; $0.window.isHidden = true }
      recomputeIsVisible()
    }
  }

  /// Filter `mounted` down to the covers that should be acted on for
  /// this transition: a specific scene's cover when targeted, every
  /// cover for the all-scenes path.
  private func coversTargeting(scene: UIWindowScene?) -> [MountedCover] {
    guard let scene else { return mounted }
    return mounted.filter { $0.window.windowScene === scene }
  }

  /// Re-derive the global `isVisible` flag from per-cover alpha + window
  /// state. With per-scene hides one scene can be hidden while others
  /// stay visible; the public flag is true iff any cover is visible.
  private func recomputeIsVisible() {
    let anyVisible = mounted.contains { !$0.window.isHidden && $0.view.alpha > 0 }
    writeIsVisible(anyVisible)
  }

  /// Build (or refresh) one cover window per active scene. Cheap when
  /// windows already exist for the right scenes — only adds windows
  /// for newly-active ones and prunes any whose scene has detached.
  private func ensureCoversForActiveScenes(visible: Bool) {
    let scenes = activeScenes()
    // Drop covers whose scene is gone (e.g. Stage Manager closed it).
    mounted.removeAll { cover in
      guard let scene = cover.window.windowScene, scenes.contains(where: { $0 === scene }) else {
        Self.dismantle(cover: cover)
        return true
      }
      return false
    }

    let existingScenes = Set(mounted.compactMap { $0.window.windowScene.map(ObjectIdentifier.init) })
    for scene in scenes where !existingScenes.contains(ObjectIdentifier(scene)) {
      let cover = buildMountedCover(for: scene, visible: visible)
      mounted.append(cover)
    }
  }

  /// Rebuild the inner view of every mounted cover from current
  /// `(color, image?, blur?)` state. Called from setColor/setImage/
  /// setBlur/clearImage so a mode change while the cover is mounted
  /// (e.g. during e2e tests, or while it's already visible from a
  /// manual `show()`) updates immediately.
  private func refreshCoverContent() {
    guard !mounted.isEmpty else { return }
    let wasVisible = isVisible
    var rebuilt: [MountedCover] = []
    rebuilt.reserveCapacity(mounted.count)
    for cover in mounted {
      let view = buildCoverView(in: cover.window.bounds)
      view.frame = cover.window.bounds
      view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      view.alpha = cover.view.alpha
      cover.view.removeFromSuperview()
      cover.window.rootViewController?.view.addSubview(view)
      let animator = objc_getAssociatedObject(view, &Self.blurAnimatorKey) as? UIViewPropertyAnimator
      cover.blurAnimator?.stopAnimation(true)
      rebuilt.append(MountedCover(window: cover.window, view: view, blurAnimator: animator))
    }
    mounted = rebuilt
    if wasVisible {
      mounted.forEach { $0.view.alpha = 1; $0.window.isHidden = false }
    }
  }

  private func tearDownAllCovers() {
    for cover in mounted { Self.dismantle(cover: cover) }
    mounted.removeAll()
    writeIsVisible(false)
  }

  /// Tear down a single mounted cover: stop the blur animator, detach
  /// the cover view, and release the window so the scene drops it.
  private static func dismantle(cover: MountedCover) {
    cover.view.layer.removeAllAnimations()
    cover.blurAnimator?.stopAnimation(true)
    cover.view.removeFromSuperview()
    cover.window.isHidden = true
    cover.window.rootViewController = nil
    // Detaching from the windowScene lets the scene release its
    // strong reference — without this the window can outlive `mounted`.
    cover.window.windowScene = nil
  }

  // MARK: - View construction

  private static var blurAnimatorKey: UInt8 = 0

  private func buildMountedCover(for scene: UIWindowScene, visible: Bool) -> MountedCover {
    let bounds = scene.coordinateSpace.bounds
    let coverWindow = UIWindow(windowScene: scene)
    // Above `.alert` (= 2000) so the cover sits unconditionally on top
    // of every other window in the scene — host content, RN Modals,
    // status bar, system alerts. The cover only mounts in
    // applicationDidEnterBackground, which doesn't fire while system
    // permission dialogs / Face ID / Apple Pay are presented (those
    // drive `applicationWillResignActive` instead), so a level above
    // `.alert` doesn't interfere with those flows in normal use.
    coverWindow.windowLevel = UIWindow.Level.alert + 1
    coverWindow.frame = bounds
    coverWindow.backgroundColor = .clear
    let rootVC = UIViewController()
    rootVC.view.backgroundColor = .clear
    coverWindow.rootViewController = rootVC

    let view = buildCoverView(in: bounds)
    view.frame = bounds
    view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    view.alpha = visible ? 1 : 0
    rootVC.view.addSubview(view)
    // isHidden = false makes the window participate in the scene's
    // visible-windows list. Avoid makeKeyAndVisible — the cover
    // doesn't need to be the key window and we don't want to steal
    // keyboard focus from the host.
    coverWindow.isHidden = !visible

    let animator = objc_getAssociatedObject(view, &Self.blurAnimatorKey) as? UIViewPropertyAnimator
    return MountedCover(window: coverWindow, view: view, blurAnimator: animator)
  }

  private func buildCoverView(in bounds: CGRect) -> UIView {
    let container = UIView(frame: bounds)
    container.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    container.accessibilityIdentifier = "CoverOverlay"
    // Don't expose to VoiceOver — the cover briefly appears during
    // foreground transitions and shouldn't steal focus.
    container.isAccessibilityElement = false
    container.accessibilityElementsHidden = true
    container.clipsToBounds = true
    container.isUserInteractionEnabled = true

    if let style = blurStyle {
      // UIVisualEffectView with a UIViewPropertyAnimator-driven
      // partial blur. Pausing the animator at fractionComplete is the
      // documented way to dial blur strength below 1.0.
      let blur = UIVisualEffectView(effect: nil)
      blur.frame = container.bounds
      blur.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      container.addSubview(blur)

      let animator = UIViewPropertyAnimator(duration: 1, curve: .linear) { [weak blur] in
        blur?.effect = UIBlurEffect(style: style.uiKitStyle)
      }
      // Scrubbing via fractionComplete only works on a paused animator.
      // Without start→pause the animator stays inactive and the effect
      // snaps to the block's final state (full blur) on next render.
      // pausesOnCompletion keeps the animator paused if intensity hits
      // 1.0 so later scrubs (e.g. dialing intensity back down) still work.
      animator.pausesOnCompletion = true
      animator.startAnimation()
      animator.pauseAnimation()
      animator.fractionComplete = blurIntensity
      objc_setAssociatedObject(
        container, &Self.blurAnimatorKey, animator,
        .OBJC_ASSOCIATION_RETAIN_NONATOMIC
      )
      return container
    }

    container.backgroundColor = backgroundColor

    if let config = imageConfig {
      let frame = imageFrame(for: config, in: container.bounds)
      let imageView = UIImageView(frame: frame)
      imageView.autoresizingMask = autoresizingMask(for: config)
      imageView.clipsToBounds = true
      imageView.contentMode = contentMode(for: config.resizeMode, x: config.x, y: config.y)
      imageView.image = config.image
      container.addSubview(imageView)
      // No fallback fetch here: setImage already kicked off the load
      // when the URI was first set, and refreshCoverContent is
      // re-invoked from its completion callback.
    }
    return container
  }

  // MARK: - Scene lookup (multi-scene aware)

  private func activeScenes() -> [UIWindowScene] {
    // Iterate every attached scene so iPad split view, Stage Manager,
    // and visionOS multi-window apps cover every visible surface,
    // not just the first one.
    var scenes: [UIWindowScene] = []
    for case let scene as UIWindowScene in UIApplication.shared.connectedScenes
        where scene.activationState != .unattached {
      scenes.append(scene)
    }
    return scenes
  }

  // MARK: - Image frame and autoresizing

  private func imageFrame(for config: ImageConfig, in bounds: CGRect) -> CGRect {
    let w = config.width > 0 ? min(config.width, bounds.width) : bounds.width
    let h = config.height > 0 ? min(config.height, bounds.height) : bounds.height

    let xOrigin: CGFloat
    switch config.x {
    case .left:   xOrigin = 0
    case .center: xOrigin = (bounds.width - w) / 2
    case .right:  xOrigin = bounds.width - w
    }

    let yOrigin: CGFloat
    switch config.y {
    case .top:    yOrigin = 0
    case .center: yOrigin = (bounds.height - h) / 2
    case .bottom: yOrigin = bounds.height - h
    }
    return CGRect(x: xOrigin, y: yOrigin, width: w, height: h)
  }

  private func autoresizingMask(for config: ImageConfig) -> UIView.AutoresizingMask {
    var mask: UIView.AutoresizingMask = []
    if config.width <= 0 { mask.insert(.flexibleWidth) }
    if config.height <= 0 { mask.insert(.flexibleHeight) }
    if config.width > 0 {
      switch config.x {
      case .left:   mask.insert(.flexibleRightMargin)
      case .right:  mask.insert(.flexibleLeftMargin)
      case .center: mask.insert(.flexibleLeftMargin); mask.insert(.flexibleRightMargin)
      }
    }
    if config.height > 0 {
      switch config.y {
      case .top:    mask.insert(.flexibleBottomMargin)
      case .bottom: mask.insert(.flexibleTopMargin)
      case .center: mask.insert(.flexibleTopMargin); mask.insert(.flexibleBottomMargin)
      }
    }
    return mask
  }

  // MARK: - Resize × position → contentMode

  private func contentMode(
    for resize: CoverResizeMode,
    x: CoverPositionX,
    y: CoverPositionY
  ) -> UIView.ContentMode {
    switch resize {
    case .stretch: return .scaleToFill
    case .cover:   return .scaleAspectFill
    case .contain: return .scaleAspectFit
    case .center:  return positionContentMode(x: x, y: y)
    }
  }

  private func positionContentMode(
    x: CoverPositionX,
    y: CoverPositionY
  ) -> UIView.ContentMode {
    switch (x, y) {
    case (.left,   .top):    return .topLeft
    case (.center, .top):    return .top
    case (.right,  .top):    return .topRight
    case (.left,   .center): return .left
    case (.center, .center): return .center
    case (.right,  .center): return .right
    case (.left,   .bottom): return .bottomLeft
    case (.center, .bottom): return .bottom
    case (.right,  .bottom): return .bottomRight
    }
  }
}
