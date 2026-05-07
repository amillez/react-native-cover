package com.margelo.nitro.cover

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Helpers for finding the topmost host window of an activity. Used to
 *  1. attach the cover panel to that view's `windowToken` so it
 *     z-orders above whatever's on top (RN `<Modal>` Dialogs create
 *     their own top-level window — see modal-coverage flow);
 *  2. install the auto-dismiss focus listener on the same window
 *     so we observe focus changes of the front-most surface (the
 *     activity's decor doesn't see focus changes while a Dialog
 *     is in front of it);
 *  3. snapshot the visible UI for blur mode.
 *
 * Walks `WindowManagerGlobal.mViews` (the per-process list of every
 * View currently attached to a Window) in last-added-first order and
 * returns the topmost decor view whose host context is the given
 * activity.
 *
 * Importantly, filters out views whose ViewRootImpl is dying — when
 * a Dialog is dismissed, `WindowManagerGlobal.removeView` calls
 * `view.assignParent(null)` and stages the view in `mDyingViews`
 * but does NOT shrink `mViews` until the next frame runs
 * `doRemoveView`. Without this filter, `Cover.show()` immediately
 * after a modal close would still capture the modal's surface as
 * the blur source. The `parent != null` check rejects those staged
 * views (a healthy attached top-level view has its ViewRootImpl
 * as parent).
 *
 * The reflection lookup (`Class.forName` + `getDeclaredField`) is
 * cached in static fields on first use, so subsequent calls are just
 * one virtual dispatch each. Falls back to null on any reflection
 * failure; the caller then uses the activity's own decor view.
 */
internal object CoverWindowAttachment {
  private const val TAG = "Cover"

  // Cached reflection handles — initialized lazily on first call,
  // sticky thereafter. `null` after a failed init means "we already
  // tried and don't try again this process".
  @Volatile private var cachedGetInstance: Method? = null
  @Volatile private var cachedMViewsField: Field? = null
  @Volatile private var reflectionInitFailed: Boolean = false

  fun topmostHostViewFor(activity: Activity, exclude: View? = null): View? {
    val views = readWindowManagerViews() ?: return null
    // Iterate in reverse: in WindowManagerGlobal the last entry is
    // the most recently attached, and on a single-activity stack
    // that maps to the topmost window.
    for (i in views.indices.reversed()) {
      val v = views[i]
      if (v === exclude) continue
      if (v.windowToken == null) continue
      // Reject views that have been detached from their ViewRootImpl
      // but are still lingering in mViews (mDyingViews entries — see
      // the doc comment above).
      if (v.parent == null) continue
      if (contextOwnsActivity(v.context, activity)) {
        return v
      }
    }
    return null
  }

  fun contextOwnsActivity(ctx: Context?, activity: Activity): Boolean {
    var c: Context? = ctx
    while (c != null) {
      if (c === activity) return true
      c = (c as? ContextWrapper)?.baseContext ?: return false
    }
    return false
  }

  private fun readWindowManagerViews(): List<View>? {
    if (reflectionInitFailed) return null
    return try {
      val getInstance = cachedGetInstance ?: run {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val method = cls.getMethod("getInstance")
        cachedGetInstance = method
        // Cache the field too while we have the class.
        if (cachedMViewsField == null) {
          cachedMViewsField = cls.getDeclaredField("mViews").apply { isAccessible = true }
        }
        method
      }
      val mViewsField = cachedMViewsField
        ?: throw IllegalStateException("mViews field not cached")
      val instance = getInstance.invoke(null) ?: return null
      @Suppress("UNCHECKED_CAST")
      mViewsField.get(instance) as? List<View>
    } catch (e: Throwable) {
      Log.w(TAG, "topmostHostViewFor: reflection failed: $e")
      reflectionInitFailed = true
      null
    }
  }
}
