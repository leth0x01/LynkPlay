package com.shilapi.xcertplay.update

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateInstallScreenshotsTest {
    @Test fun pendingUpdateAtLargeFontsInCompactFullAndRtlLayouts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        SessionInstallState.begin(activity, 42)
        SessionInstallState.saveConfirmation(activity, 42, Intent("test.CONFIRM"))
        for ((name, widthDp, language) in listOf(
            Triple("compact-en", 320, "en-US"),
            Triple("full-en", 960, "en-US"),
            Triple("compact-ar-rtl", 320, "ar-SA"),
        )) {
            val locale = Locale.forLanguageTag(language)
            val configuration = Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f
                setLocale(locale)
                setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            val row = DiPlayActivity::class.java.getDeclaredMethod("updateRow")
                .apply { isAccessible = true }.invoke(activity) as View
            assertTrue(views(row).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.update_installing)
            })
            val width = (widthDp * activity.resources.displayMetrics.density).toInt()
            capture(row, width, name)

            SessionInstallState.result(activity, 42, android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED)
            val apk = File(activity.cacheDir, "confirmed-update.apk").apply { writeBytes(byteArrayOf(1)) }
            DiPlayActivity::class.java.getDeclaredField("updateFile").apply { isAccessible = true }.set(activity, apk)
            DiPlayActivity::class.java.getDeclaredMethod("confirmUpdateInstallation")
                .apply { isAccessible = true }.invoke(activity)
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            try {
                capture(dialog.window!!.decorView, width, "$name-confirmation")
            } finally {
                dialog.dismiss()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            SessionInstallState.begin(activity, 42)
            SessionInstallState.saveConfirmation(activity, 42, Intent("test.CONFIRM"))
        }
    }

    private fun capture(view: View, width: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/update-install-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
