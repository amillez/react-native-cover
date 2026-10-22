package com.margelo.nitro.cover

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import androidx.annotation.RequiresApi

/// `CoverMode.SYSTEM` on Android 13+. The activity opts out of Recents
/// screenshots, and the system then draws the Recents card itself, filled
/// with the task's background color (AOSP `AbsAppSnapshotController
/// .drawAppThemeSnapshot`). No window is added, so there is no race with
/// the snapshot: the system never takes one.
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object SystemRecentsCover {
  fun apply(activity: Activity, color: Int) {
    activity.setRecentsScreenshotEnabled(false)
    activity.setTaskDescription(taskDescription(color))
  }

  fun clear(activity: Activity) {
    activity.setRecentsScreenshotEnabled(true)
    activity.setTaskDescription(taskDescription(themeBackground(activity)))
  }

  // TaskDescription rejects a translucent background color, and the system
  // draws the card opaque anyway.
  private fun taskDescription(color: Int): ActivityManager.TaskDescription =
    ActivityManager.TaskDescription.Builder()
      .setBackgroundColor(color or 0xFF000000.toInt())
      .build()

  // The color the system derives from the theme when nothing overrides it.
  private fun themeBackground(activity: Activity): Int {
    val value = TypedValue()
    val resolved = activity.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
    return if (resolved && value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
      value.data
    } else {
      Color.BLACK
    }
  }
}
