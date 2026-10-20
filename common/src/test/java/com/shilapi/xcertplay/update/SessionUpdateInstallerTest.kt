package com.shilapi.xcertplay.update

import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class SessionUpdateInstallerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun replacementFlagIsAddedWithoutChangingOtherFlags() {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
            .apply { isAccessible = true }
        val original = field.getInt(params) and 2.inv()
        field.setInt(params, original)
        SessionUpdateInstaller.allowReplacement(params)
        assertEquals(original or 2, field.getInt(params))
        SessionUpdateInstaller.allowReplacement(params)
        assertEquals(original or 2, field.getInt(params))
    }

    @Test fun missingApkDoesNotLeaveSession() {
        assertFalse(SessionUpdateInstaller.install(context, File(context.cacheDir, "missing.apk")))
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun pendingUserActionOpensConfirmation() {
        val confirmation = Intent("test.CONFIRM_INSTALL")
        UpdateInstallResultReceiver().handleResult(context, result(PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirmation))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(confirmation.action, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(SessionInstallState.read(context)!!.confirmationAvailable)
        SessionUpdateInstaller.confirmPending(context)
        assertEquals(confirmation.action, shadowOf(context).nextStartedActivity.action)
    }

    @Test fun failureShowsErrorWithoutOpeningFileProvider() {
        UpdateInstallResultReceiver().handleResult(context, result(PackageInstaller.STATUS_FAILURE_INVALID))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun successDoesNotOpenInstaller() {
        UpdateInstallResultReceiver().handleResult(context, result(PackageInstaller.STATUS_SUCCESS))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun systemAppIdIsRecognizedAcrossUsers() {
        assertTrue(SessionUpdateInstaller.isSystemUid(1000))
        assertTrue(SessionUpdateInstaller.isSystemUid(1_001_000))
        assertFalse(SessionUpdateInstaller.isSystemUid(10_000))
        assertFalse(SessionUpdateInstaller.isSystemUid(2000))
    }

    @Test fun oldCallbackCannotClearNewSession() {
        SessionInstallState.begin(context, 42)
        UpdateInstallResultReceiver().handleResult(context,
            Intent("${context.packageName}.UPDATE_INSTALL_RESULT")
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, 41)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
        assertTrue(SessionInstallState.read(context)!!.pending)
        assertEquals(42, SessionInstallState.read(context)!!.id)
    }

    @Test fun missingConfirmationAbandonsSessionAndUnlocksRetry() {
        UpdateInstallResultReceiver().handleResult(context, result(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertFalse(SessionInstallState.read(context)!!.pending)
    }

    @Test fun abandonedCopySessionCanBeCancelledAfterProcessDeath() {
        val id = context.packageManager.packageInstaller.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        SessionInstallState.begin(context, id)
        assertTrue(SessionInstallState.reconcile(context)!!.pending)
        SessionUpdateInstaller.cancelPending(context)
        assertFalse(SessionInstallState.read(context)!!.pending)
        assertTrue(context.packageManager.packageInstaller.mySessions.none { it.sessionId == id })
    }

    @Test fun endedSessionWithoutCallbackDoesNotLeavePermanentLock() {
        SessionInstallState.begin(context, 999)
        assertFalse(SessionInstallState.reconcile(context)!!.pending)
    }

    @Test fun terminalCallbackClearsConfirmation() {
        SessionInstallState.begin(context, 42)
        SessionInstallState.saveConfirmation(context, 42, Intent("test.CONFIRM"))
        assertTrue(SessionInstallState.read(context)!!.confirmationAvailable)
        SessionInstallState.result(context, 42, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertFalse(SessionInstallState.read(context)!!.confirmationAvailable)
    }

    @Test fun receiverIsNotExported() {
        val info = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, UpdateInstallResultReceiver::class.java), 0)
        assertFalse(info.exported)
    }

    private fun result(status: Int): Intent {
        SessionInstallState.begin(context, 42)
        return Intent("${context.packageName}.UPDATE_INSTALL_RESULT")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
    }
}
