//
//  CoverImageFetcher.swift
//  react-native-cover
//
//  Decodes image URIs off the main thread and delivers the result back
//  on main. Holds a reference to the in-flight HTTP task so a subsequent
//  request can cancel the previous one when the URI changes — releasing
//  the network resource instead of letting the wasted bytes finish
//  downloading just to be discarded.
//

import Foundation
import UIKit

final class CoverImageFetcher {
  /// Background queue used for `data:`, file, and asset decoding. HTTP
  /// fetches use `URLSession.shared` and never block this queue.
  private let queue = DispatchQueue(label: "react-native-cover.image", qos: .userInitiated)

  /// The currently-in-flight HTTP task, if any. Held weakly is wrong —
  /// we own the lifecycle, not URLSession (its strong ref is internal).
  /// Always touched on `queue` to keep cancel/load races in sync.
  private var inflightTask: URLSessionDataTask?

  /// Cancels any in-flight HTTP task. Safe to call from any thread —
  /// dispatches to the internal queue.
  func cancelInflight() {
    queue.async { [weak self] in
      self?.inflightTask?.cancel()
      self?.inflightTask = nil
    }
  }

  /// Asynchronously load an image. The completion handler runs on the
  /// main thread.
  func load(uri: String, completion: @escaping (UIImage?) -> Void) {
    queue.async { [weak self] in
      guard let self else { return }
      // Synchronous decoders for non-HTTP sources — fast enough to run
      // inline on this queue.
      if uri.hasPrefix("data:") {
        let image = Self.decodeData(uri: uri)
        Self.deliver(image, on: completion)
        return
      }
      if uri.hasPrefix("/") {
        let image = UIImage(contentsOfFile: uri)
        Self.warnIfNil(image, uri: uri)
        Self.deliver(image, on: completion)
        return
      }
      if uri.hasPrefix("file://") {
        let path = String(uri.dropFirst("file://".count))
        let image = UIImage(contentsOfFile: path)
        Self.warnIfNil(image, uri: uri)
        Self.deliver(image, on: completion)
        return
      }
      if let url = URL(string: uri), url.scheme == "http" || url.scheme == "https" {
        self.fetchHTTP(url: url, completion: completion)
        return
      }
      NSLog("[Cover] failed to load image at: %@", uri)
      DispatchQueue.main.async { completion(nil) }
    }
  }

  /// Force-decode bitmap data on the loader queue and hop to main.
  ///
  /// `UIImage(contentsOfFile:)` and `UIImage(data:)` defer the actual
  /// PNG/JPEG decode until the image is first composited. Without
  /// this step, the *very first* paint of the cover after a
  /// background-mount pays the decode cost on the main thread —
  /// exactly the frame the OS captures for the App Switcher snapshot.
  /// `preparingForDisplay()` (iOS 15+) eagerly produces a decoded
  /// CGImage on this queue so the first paint is just a blit. On
  /// older iOS we fall back to the deferred-decode image.
  private static func deliver(_ image: UIImage?, on completion: @escaping (UIImage?) -> Void) {
    guard let image else {
      DispatchQueue.main.async { completion(nil) }
      return
    }
    let prepared: UIImage
    if #available(iOS 15.0, visionOS 1.0, *) {
      prepared = image.preparingForDisplay() ?? image
    } else {
      prepared = image
    }
    DispatchQueue.main.async { completion(prepared) }
  }

  // MARK: - Private

  private func fetchHTTP(url: URL, completion: @escaping (UIImage?) -> Void) {
    // Cancel any prior request before we start a new one. We're already
    // on `queue`, so this assignment is the source of truth.
    inflightTask?.cancel()

    var request = URLRequest(url: url)
    request.timeoutInterval = 10

    let task = URLSession.shared.dataTask(with: request) { [weak self] data, _, error in
      if let error = error as? URLError, error.code == .cancelled {
        // Cancelled: the caller has already moved on. Don't deliver.
        return
      }
      if let error {
        NSLog("[Cover] image fetch error %@: %@", url.absoluteString, "\(error)")
        DispatchQueue.main.async { completion(nil) }
        return
      }
      guard let data, let image = UIImage(data: data) else {
        NSLog("[Cover] failed to decode HTTP image at: %@", url.absoluteString)
        DispatchQueue.main.async { completion(nil) }
        return
      }
      // Hop onto the loader queue to force-decode the bitmap before
      // delivering on main — same rationale as `deliver(_:on:)`.
      // URLSession's completion runs on its own delegate queue, not
      // ours, so `preparingForDisplay()` here would block that queue.
      self?.queue.async { Self.deliver(image, on: completion) }
    }
    inflightTask = task
    task.resume()
  }

  private static func decodeData(uri: String) -> UIImage? {
    guard let url = URL(string: uri),
          let data = try? Data(contentsOf: url) else { return nil }
    let image = UIImage(data: data)
    if image == nil {
      NSLog("[Cover] failed to decode data: URI")
    }
    return image
  }

  private static func warnIfNil(_ image: UIImage?, uri: String) {
    if image == nil {
      NSLog("[Cover] failed to load image at: %@", uri)
    }
  }
}
