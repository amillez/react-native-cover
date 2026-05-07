package com.margelo.nitro.cover

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes image URIs off the main thread and delivers the bitmap back
 * on main. Holds a reference to the in-flight HTTP connection so a
 * subsequent request can disconnect it instead of letting wasted bytes
 * finish downloading just to be discarded.
 *
 * Supports the URI shapes that survive RN bundling on Android:
 *  - `data:` (base64 or percent-encoded payload)
 *  - `file://`
 *  - `file:///android_asset/<path>` (released-app bundled assets via
 *    AssetManager — distinct from the regular `file://` filesystem path)
 *  - `http(s)://`
 *  - bare absolute filesystem paths (`/sdcard/foo.png`)
 *  - bare resource names from `Image.resolveAssetSource(require(...))`
 *    in release builds — RN packs them as drawables; we resolve via
 *    `Resources.getIdentifier`
 */
internal class CoverImageLoader(private val applicationContext: Context) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "react-native-cover.image").apply { isDaemon = true }
  }
  private val inflightConnection = AtomicReference<HttpURLConnection?>(null)

  fun cancelInflight() {
    inflightConnection.getAndSet(null)?.let { conn ->
      // disconnect() releases the underlying socket; the worker
      // thread's read on `inputStream` then surfaces an IOException
      // which we swallow as a normal cancellation.
      try { conn.disconnect() } catch (_: Throwable) {}
    }
  }

  /// Load an image and downsample it to fit the target box. Pass
  /// `targetWidthPx`/`targetHeightPx <= 0` for "use full screen size"
  /// — the cover is always full-screen, so the screen is the natural
  /// upper bound. Decoded bitmap is scaled to roughly target size via
  /// `BitmapFactory.Options.inSampleSize` (powers of two) so a 4 MP
  /// remote photo doesn't allocate ~50 MB just to be displayed in a
  /// 240×240 dp icon slot.
  fun load(uri: String, targetWidthPx: Int, targetHeightPx: Int, callback: (Bitmap?) -> Unit) {
    val tw = if (targetWidthPx > 0) targetWidthPx
             else applicationContext.resources.displayMetrics.widthPixels
    val th = if (targetHeightPx > 0) targetHeightPx
             else applicationContext.resources.displayMetrics.heightPixels
    executor.execute {
      val bitmap = decode(uri, tw, th)
      if (bitmap == null) {
        Log.w(TAG, "failed to load image at: $uri")
      }
      mainHandler.post { callback(bitmap) }
    }
  }

  private fun decode(uri: String, targetW: Int, targetH: Int): Bitmap? {
    return try {
      when {
        uri.startsWith("data:") -> decodeDataUri(uri, targetW, targetH)
        uri.startsWith("file:///android_asset/") -> {
          decodeAsset(uri.removePrefix("file:///android_asset/"), targetW, targetH)
        }
        uri.startsWith("file://") -> decodeFilePath(uri.removePrefix("file://"), targetW, targetH)
        uri.startsWith("/") -> decodeFilePath(uri, targetW, targetH)
        uri.startsWith("http://") || uri.startsWith("https://") -> decodeHttp(uri, targetW, targetH)
        else -> {
          // Try as a filesystem path first, then as a packaged drawable
          // resource name (what RN's resolveAssetSource returns in
          // release builds), then as an Android asset path.
          val f = File(uri)
          when {
            f.exists() -> decodeFilePath(f.absolutePath, targetW, targetH)
            else -> decodeResourceName(uri, targetW, targetH) ?: decodeAsset(uri, targetW, targetH)
          }
        }
      }
    } catch (e: Throwable) {
      Log.w(TAG, "image fetch error $uri: $e")
      null
    }
  }

  /// Two-pass file decode: read just the bounds first, compute
  /// `inSampleSize`, then decode at the reduced size.
  private fun decodeFilePath(path: String, targetW: Int, targetH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val opts = BitmapFactory.Options().apply {
      inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
    }
    return BitmapFactory.decodeFile(path, opts)
  }

  /// Compute a power-of-two `inSampleSize` so the decoded bitmap is
  /// the smallest size that still fully covers the target box. Returns
  /// 1 (no downsample) when source dimensions are unknown or already
  /// smaller than the target.
  private fun computeSampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
    var sample = 1
    var w = srcW
    var h = srcH
    while (w / 2 >= targetW && h / 2 >= targetH) {
      w /= 2
      h /= 2
      sample *= 2
    }
    return sample
  }

  private fun decodeDataUri(uri: String, targetW: Int, targetH: Int): Bitmap? {
    val comma = uri.indexOf(',')
    if (comma <= 0) return null
    val header = uri.substring(0, comma)
    val payload = uri.substring(comma + 1)
    val bytes = if (header.contains(";base64", ignoreCase = true)) {
      Base64.decode(payload, Base64.DEFAULT)
    } else {
      percentDecode(payload)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply {
      inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }

  /// Byte-level percent decoder. Unlike `URLDecoder.decode`, we do not
  /// fold `+` to space — `data:` URIs are RFC 3986 not form-encoded.
  private fun percentDecode(input: String): ByteArray {
    val out = ByteArrayOutputStream(input.length)
    var i = 0
    while (i < input.length) {
      val c = input[i]
      if (c == '%' && i + 2 < input.length) {
        val hi = Character.digit(input[i + 1], 16)
        val lo = Character.digit(input[i + 2], 16)
        if (hi >= 0 && lo >= 0) {
          out.write((hi shl 4) or lo)
          i += 3
          continue
        }
      }
      out.write(c.code)
      i++
    }
    return out.toByteArray()
  }

  private fun decodeAsset(path: String, targetW: Int, targetH: Int): Bitmap? {
    val assets: AssetManager = applicationContext.assets
    return try {
      // AssetManager streams aren't seekable, so decode bounds and
      // pixels from two separate `assets.open` handles.
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
      val opts = BitmapFactory.Options().apply {
        inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
      }
      assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Throwable) {
      null
    }
  }

  /// Try to decode `name` as a packaged drawable resource — the format
  /// `resolveAssetSource` returns for `require()`-bundled assets in
  /// release builds.
  private fun decodeResourceName(name: String, targetW: Int, targetH: Int): Bitmap? {
    if (name.isEmpty()) return null
    val resources = applicationContext.resources
    val packageName = applicationContext.packageName
    // `getIdentifier` checks `drawable`, `mipmap`, and `raw` so a file
    // packed as `drawable-xxhdpi/icon.png` resolves the same as
    // `mipmap-xxhdpi/icon.png` or `raw/icon`.
    for (kind in arrayOf("drawable", "mipmap", "raw")) {
      val id = resources.getIdentifier(name, kind, packageName)
      if (id != 0) {
        return try {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeResource(resources, id, bounds)
          val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
          }
          BitmapFactory.decodeResource(resources, id, opts)
        } catch (_: Throwable) {
          null
        }
      }
    }
    return null
  }

  private fun decodeHttp(uri: String, targetW: Int, targetH: Int): Bitmap? {
    val conn = (URL(uri).openConnection() as HttpURLConnection).apply {
      connectTimeout = 10_000
      readTimeout = 10_000
      instanceFollowRedirects = true
    }
    inflightConnection.getAndSet(conn)?.let { prev ->
      // Should be rare (caller is supposed to cancel before
      // re-issuing) but be defensive.
      try { prev.disconnect() } catch (_: Throwable) {}
    }
    return try {
      // Buffer the body once: HttpURLConnection's stream isn't
      // seekable, so a two-pass bounds-then-pixels decode would need
      // to re-issue the request. Reading into memory and decoding
      // twice from a byte array is faster and uses less network.
      val bytes = conn.inputStream.use { it.readAllBytesCompat() }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val opts = BitmapFactory.Options().apply {
        inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
      }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Throwable) {
      null
    } finally {
      // Clear our slot only if still owned — a newer request may have
      // already replaced us.
      inflightConnection.compareAndSet(conn, null)
      try { conn.disconnect() } catch (_: Throwable) {}
    }
  }

  /// `InputStream.readAllBytes` is API 33+; this is a small back-port
  /// so we keep our minSdk 23 floor.
  private fun InputStream.readAllBytesCompat(): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
      val n = read(buf)
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  companion object {
    private const val TAG = "Cover"
  }
}
