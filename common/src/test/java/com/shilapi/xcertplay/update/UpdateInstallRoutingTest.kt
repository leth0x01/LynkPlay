package com.shilapi.xcertplay.update

import android.content.pm.PackageInstaller
import com.shilapi.xcertplay.DiPlayActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class UpdateInstallRoutingTest {
    private fun activity(): DiPlayActivity {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.updateInstallWorker = { it.run() }
        activity.updateInstallerUid = { 1_001_000 }
        val apk = File(activity.cacheDir, "verified.apk").apply { writeBytes(byteArrayOf(1)) }
        DiPlayActivity::class.java.getDeclaredField("updateFile").apply { isAccessible = true }.set(activity, apk)
        return activity
    }

    private fun install(activity: DiPlayActivity) {
        DiPlayActivity::class.java.getDeclaredMethod("installUpdate").apply { isAccessible = true }.invoke(activity)
    }

    private fun confirm(activity: DiPlayActivity): android.app.AlertDialog {
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        DiPlayActivity::class.java.getDeclaredMethod("confirmUpdateInstallation")
            .apply { isAccessible = true }.invoke(activity)
        return org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
    }

    @Test fun installationStartsOnlyAfterExplicitConfirmation() {
        val activity = activity()
        var calls = 0
        activity.sessionUpdateInstaller = { calls++; false }
        val dialog = confirm(activity)
        assertEquals(0, calls)
        assertSame(dialog, confirm(activity))
        assertEquals(activity.getString(com.shilapi.xcertplay.host.R.string.update_install_warning),
            dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString())
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, calls)
    }

    @Test fun cancellingConfirmationNeverStartsInstallation() {
        val activity = activity()
        var calls = 0
        activity.sessionUpdateInstaller = { calls++; false }
        confirm(activity).getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, calls)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun changedDownloadInvalidatesOpenConfirmation() {
        val activity = activity()
        var calls = 0
        activity.sessionUpdateInstaller = { calls++; false }
        val dialog = confirm(activity)
        DiPlayActivity::class.java.getDeclaredField("updateGeneration")
            .apply { isAccessible = true }.setInt(activity, 1)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, calls)
    }

    @Test fun submittedSessionBlocksRepeatClicksIncludingAnotherActivity() {
        val activity = activity()
        var calls = 0
        activity.sessionUpdateInstaller = {
            calls++
            SessionInstallState.begin(activity, 42)
            true
        }
        install(activity)
        install(activity)
        val recreated = activity()
        recreated.sessionUpdateInstaller = { calls++; true }
        install(recreated)
        assertEquals(1, calls)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(recreated).nextStartedActivity)
    }

    @Test fun failedSubmissionNeverFallsBackToProviderAndCanRetry() {
        val activity = activity()
        var calls = 0
        activity.sessionUpdateInstaller = { calls++; false }
        install(activity)
        install(activity)
        assertEquals(2, calls)
        assertNull(shadowOf(activity).nextStartedActivity)
        val field = DiPlayActivity::class.java.getDeclaredField("updateStage").apply { isAccessible = true }
        assertEquals("FAILED", field.get(activity).toString())
    }

    @Test fun terminalResultReleasesSessionLock() {
        val activity = activity()
        SessionInstallState.begin(activity, 42)
        SessionInstallState.result(activity, 42, PackageInstaller.STATUS_FAILURE_ABORTED)
        var calls = 0
        activity.sessionUpdateInstaller = { calls++; false }
        install(activity)
        assertEquals(1, calls)
    }

}
