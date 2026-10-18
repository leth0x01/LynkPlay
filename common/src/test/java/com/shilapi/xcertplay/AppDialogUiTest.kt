package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppDialogUiTest {
    @Test fun hotspotDialogsKeepValidationPasswordVisibilityAndSaveWithLargeFonts() {
        for ((width, locale, density, night) in listOf(
            Scenario(360, "zh-rCN", "mdpi", true),
            Scenario(1280, "zh-rCN", "xhdpi", false),
            Scenario(600, "ar", "mdpi", true),
        )) {
            withActivity(width, locale, density, night) { activity ->
                var saved: Triple<String, String, ManualHotspotSecurity>? = null
                AirPlayPersistence.saveManualHotspotSsid(activity, "Lynk06")
                AirPlayPersistence.saveManualHotspotPassphrase(activity, "12345678")
                var savedInterface: String? = null
                val callback: (String, String, ManualHotspotSecurity, String) -> Unit = { name, password, security, networkInterface ->
                    savedInterface = networkInterface
                    saved = Triple(name, password, security)
                }
                activity.javaClass.declaredMethods.single { it.name == "askHotspotCredentials" }
                    .apply { isAccessible = true }.invoke(activity, false, callback)
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                try {
                    val inputs = descendants(dialog.window!!.decorView).filterIsInstance<EditText>()
                    assertEquals(3, inputs.size)
                    inputs[2].setText("eth0.3")
                    assertEquals(DiPlayPalette.of(night).primaryText, inputs.first().currentTextColor)
                    assertFalse(inputs.first().background is android.graphics.drawable.ColorDrawable)
                    val password = inputs[1]
                    val visibility = descendants(dialog.window!!.decorView).filterIsInstance<CheckBox>().single()
                    visibility.isChecked = true
                    assertNull(password.transformationMethod)
                    visibility.isChecked = false
                    assertNotNull(password.transformationMethod)
                    capture(dialog, "$locale-${width}dp-$density-hotspot")

                    password.setText("short")
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(dialog.isShowing)
                    assertNull(saved)
                    password.setText("12345678")
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertFalse(dialog.isShowing)
                    assertEquals("Lynk06", saved?.first)
                    assertEquals("12345678", saved?.second)
                    assertEquals("eth0.3", savedInterface)
                } finally { dialog.dismiss() }
            }
        }
    }

    @Test fun selectionAndConfirmationKeepCallbacksAndAppColors() {
        withActivity(360, "zh-rCN", "mdpi", true) { activity ->
            var saved = -1
            var pending = 1
            val dialog = activity.appDialogBuilder().setTitle(R.string.frame_rate)
                .setSingleChoiceItems(arrayOf(activity.getString(R.string.s_30_fps_lighter_load),
                    activity.getString(R.string.s_60_fps_smoother_motion)), 1) { _, index -> pending = index }
                .setPositiveButton(R.string.save) { _, _ -> saved = pending }
                .setNegativeButton(R.string.cancel, null).show()
            try {
                assertEquals(1, dialog.listView.checkedItemPosition)
                capture(dialog, "zh-360dp-choice")
                dialog.listView.performItemClick(dialog.listView.adapter.getView(0, null, dialog.listView), 0, 0)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, saved)
            } finally { dialog.dismiss() }

            val confirmation = activity.appDialogBuilder().setTitle(R.string.reset_carplay_wi_fi_2)
                .setMessage(R.string.settings_discard_pending_message)
                .setPositiveButton(R.string.save) { _, _ -> saved++ }
                .setNegativeButton(R.string.cancel, null).show()
            try {
                capture(confirmation, "zh-360dp-confirmation")
                confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, saved)
            } finally { confirmation.dismiss() }
        }
    }

    @Test fun timePickerKeepsItsSelectionWithTheAppDialogShell() {
        withActivity(600, "zh-rCN", "mdpi", false) { activity ->
            var selected: Pair<Int, Int>? = null
            val dialog = TimePickerDialog(activity, activity.appDialogTheme(),
                { _, hour, minute -> selected = hour to minute }, 20, 30, true).apply {
                create()
                applyAppDialogStyle(DiPlayPalette.LIGHT)
                show()
            }
            try {
                capture(dialog, "zh-600dp-time")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(20 to 30, selected)
            } finally { dialog.dismiss() }
        }
    }

    @Test fun shortLandscapeWindowKeepsHotspotActionsVisible() {
        for ((width, height, density, name) in listOf(
            Landscape(800, 400, "mdpi", "zh-800x400dp-existing-wifi"),
            Landscape(960, 360, "xhdpi", "zh-1920x720px-hotspot"),
            Landscape(1800, 670, "mdpi", "lynk06-160dpi-hotspot"),
            Landscape(1800, 400, "mdpi", "lynk06-keyboard-height-hotspot"),
        )) {
            withActivity(width, "zh-rCN", density, true, height = height) { activity ->
                if (density == "xhdpi") {
                    assertEquals(1920, (activity.resources.configuration.screenWidthDp * activity.resources.displayMetrics.density).toInt())
                    assertEquals(720, (activity.resources.configuration.screenHeightDp * activity.resources.displayMetrics.density).toInt())
                }
                val callback: (String, String, ManualHotspotSecurity, String) -> Unit = { _, _, _, _ -> }
                activity.javaClass.declaredMethods.single { it.name == "askHotspotCredentials" }
                    .apply { isAccessible = true }.invoke(activity, density == "mdpi" && width != 1800, callback)
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                try {
                    if (width == 1800) {
                        assertEquals(if (height >= 600) 960 else 640, dialog.window!!.attributes.width)
                        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().first()
                        assertEquals(if (height >= 600) 20f else 18f,
                            input.textSize / input.resources.displayMetrics.scaledDensity, 0.1f)
                    }
                    capture(dialog, name)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(dialog.isShowing)
                } finally { dialog.dismiss() }
            }
        }
    }

    @Test fun wideHotspotDialogKeepsActionsInKeyboardSizedViewport() {
        withActivity(1800, "zh-rCN", "mdpi", true, height = 670) { activity ->
            val callback: (String, String, ManualHotspotSecurity, String) -> Unit = { _, _, _, _ -> }
            activity.javaClass.declaredMethods.single { it.name == "askHotspotCredentials" }
                .apply { isAccessible = true }.invoke(activity, false, callback)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            try {
                assertEquals(960, dialog.window!!.attributes.width)
                capture(dialog, "lynk06-wide-keyboard-viewport", maxHeight = 400)
            } finally { dialog.dismiss() }
        }
    }

    private data class Scenario(val width: Int, val locale: String, val density: String, val night: Boolean)
    private data class Landscape(val width: Int, val height: Int, val density: String, val name: String)

    private fun withActivity(width: Int, locale: String, density: String, night: Boolean, height: Int = 960, body: (DiPlayActivity) -> Unit) {
        RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h${height}dp-$density")
        val app = RuntimeEnvironment.getApplication()
        val previousFlags = app.applicationInfo.flags
        // 与 mobile manifest 的 supportsRtl 配置一致。
        app.applicationInfo.flags = previousFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val previous = Configuration(app.resources.configuration)
        app.resources.updateConfiguration(Configuration(previous).apply { fontScale = 1.5f }, app.resources.displayMetrics)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity, "appNight", night)
        ReflectionHelpers.setField(activity, "palette", DiPlayPalette.of(night))
        try { body(activity) } finally {
            activity.finish()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            app.resources.updateConfiguration(previous, app.resources.displayMetrics)
            app.applicationInfo.flags = previousFlags
        }
    }

    private fun capture(dialog: AlertDialog, name: String, maxHeight: Int? = null) {
        shadowOf(Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        val width = dialog.window!!.attributes.width
        val height = maxHeight ?: (dialog.context.resources.configuration.screenHeightDp * dialog.context.resources.displayMetrics.density).toInt()
        assertTrue(width <= (dialog.context.resources.configuration.screenWidthDp * dialog.context.resources.displayMetrics.density).toInt())
        repeat(3) {
            decor.forceLayout()
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
        for (id in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(id) ?: continue
            if (button.visibility != View.VISIBLE) continue
            val visible = Rect()
            assertTrue("弹窗操作按钮必须可见", button.getGlobalVisibleRect(visible))
            assertEquals("按钮不能被裁切", button.height, visible.height())
            assertTrue("按钮文字不能被截断", button.layout.lineCount == 0 ||
                (0 until button.layout.lineCount).all { button.layout.getEllipsisCount(it) == 0 })
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            val directory = File("build/reports/app-dialog-ui").apply { mkdirs() }
            File(directory, "$name-font-1.5.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
