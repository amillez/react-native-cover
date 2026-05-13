package com.margelo.nitro.cover

import android.util.Log
import android.view.SurfaceControl
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection helper for obtaining the `SurfaceControl` backing a
 * top-level view's window. Used to apply alpha changes directly at the
 * SurfaceFlinger level via `SurfaceControl.Transaction`, bypassing both
 * the RN main-thread message queue and the `ViewRootImpl` traversal
 * pipeline.
 *
 * `ViewRootImpl.getSurfaceControl()` has been in AOSP since API 29 but
 * is annotated `@hide` / `@UnsupportedAppUsage`. On API 28+ the runtime
 * makes hidden methods APPEAR non-existent to direct reflection from
 * non-system app code (`getMethod`/`getDeclaredMethod` throw
 * `NoSuchMethodException`). We try three resolution strategies in
 * order, caching the first hit:
 *
 *   1. Direct `getDeclaredMethod("getSurfaceControl")` walking the
 *      class hierarchy + `setAccessible`. Works on devices that don't
 *      enforce hidden-API restrictions for this method.
 *   2. Direct `getDeclaredField("mSurfaceControl")` walking the
 *      hierarchy. The field is older than the method and tends to be
 *      less heavily restricted.
 *   3. Meta-reflection: invoke `Class.class.getDeclaredMethod` /
 *      `getDeclaredField` via reflection on `Class.class` itself. The
 *      hidden-API filter tracks the immediate caller of the reflective
 *      lookup; reflecting on `Class.class` makes the call appear to
 *      come from framework code, often bypassing the filter.
 *
 * On full failure the helper disables itself for the process and
 * callers fall back to `view.alpha` (the slow path).
 */
internal object SurfaceControlAccess {
  private const val TAG = "Cover"

  @Volatile private var resolvedClass: Class<*>? = null
  @Volatile private var resolvedMethod: Method? = null
  @Volatile private var resolvedField: Field? = null
  @Volatile private var reflectionFailed: Boolean = false

  fun getSurfaceControl(view: View): SurfaceControl? {
    if (reflectionFailed) return null
    val parent = view.parent
    if (parent == null) {
      Log.w(TAG, "SC capture: view.parent is null")
      return null
    }
    val cls = parent.javaClass
    return try {
      // Fast path: cached resolution.
      if (resolvedClass === cls) {
        resolvedMethod?.let { return it.invoke(parent) as? SurfaceControl }
        resolvedField?.let { return it.get(parent) as? SurfaceControl }
      }

      // Strategy 1: direct method, walking the hierarchy.
      var current: Class<*>? = cls
      while (current != null) {
        try {
          val m = current.getDeclaredMethod("getSurfaceControl").apply {
            isAccessible = true
          }
          val sc = m.invoke(parent) as? SurfaceControl
          if (sc != null) {
            resolvedClass = cls
            resolvedMethod = m
            Log.i(TAG, "SC capture: resolved via DIRECT method on ${current.name} (parent=${cls.name})")
            return sc
          }
        } catch (_: NoSuchMethodException) {
          // try parent
        }
        current = current.superclass
      }

      // Strategy 2: direct field, walking the hierarchy.
      current = cls
      while (current != null) {
        try {
          val f = current.getDeclaredField("mSurfaceControl").apply {
            isAccessible = true
          }
          val sc = f.get(parent) as? SurfaceControl
          if (sc != null) {
            resolvedClass = cls
            resolvedField = f
            Log.i(TAG, "SC capture: resolved via DIRECT field on ${current.name} (parent=${cls.name})")
            return sc
          }
        } catch (_: NoSuchFieldException) {
          // try parent
        }
        current = current.superclass
      }

      // Strategy 3: meta-reflection bypass for hidden-API enforcement.
      tryMetaReflection(parent, cls)?.let { return it }

      Log.w(TAG, "SC capture: ALL strategies failed on ${cls.name} (super chain: ${superChainNames(cls)})")
      reflectionFailed = true
      null
    } catch (e: Throwable) {
      Log.w(TAG, "SC capture: reflection threw on ${cls.name}: $e")
      reflectionFailed = true
      null
    }
  }

  private fun tryMetaReflection(parent: Any, cls: Class<*>): SurfaceControl? {
    return try {
      // Reflect on Class.class to invoke its declared-member lookups
      // from a caller stack frame that appears to be framework code.
      val classClass = Class::class.java
      val getDeclaredMethod = classClass.getMethod(
        "getDeclaredMethod",
        String::class.java,
        arrayOf<Class<*>>()::class.java,
      )
      // Try method first.
      try {
        val method = getDeclaredMethod.invoke(cls, "getSurfaceControl", arrayOf<Class<*>>()) as? Method
        if (method != null) {
          method.isAccessible = true
          val sc = method.invoke(parent) as? SurfaceControl
          if (sc != null) {
            resolvedClass = cls
            resolvedMethod = method
            Log.i(TAG, "SC capture: resolved via META-reflection METHOD on ${cls.name}")
            return sc
          }
        }
      } catch (e: Throwable) {
        Log.w(TAG, "SC capture: meta-reflection method failed on ${cls.name}: $e")
      }

      // Fall back to field.
      val getDeclaredField = classClass.getMethod("getDeclaredField", String::class.java)
      try {
        val field = getDeclaredField.invoke(cls, "mSurfaceControl") as? Field
        if (field != null) {
          field.isAccessible = true
          val sc = field.get(parent) as? SurfaceControl
          if (sc != null) {
            resolvedClass = cls
            resolvedField = field
            Log.i(TAG, "SC capture: resolved via META-reflection FIELD on ${cls.name}")
            return sc
          }
        }
      } catch (e: Throwable) {
        Log.w(TAG, "SC capture: meta-reflection field failed on ${cls.name}: $e")
      }

      null
    } catch (e: Throwable) {
      Log.w(TAG, "SC capture: meta-reflection setup threw on ${cls.name}: $e")
      null
    }
  }

  private fun superChainNames(cls: Class<*>): String {
    val sb = StringBuilder()
    var current: Class<*>? = cls.superclass
    while (current != null && current != Any::class.java) {
      if (sb.isNotEmpty()) sb.append(" -> ")
      sb.append(current.name)
      current = current.superclass
    }
    return sb.toString().ifEmpty { "(none)" }
  }
}
