package com.shilapi.xcertplay.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import java.io.File

/** Streams the verified APK instead of granting a system-UID FileProvider URI. */
internal object SessionUpdateInstaller {
    private const val INSTALL_REPLACE_EXISTING = 0x00000002

    internal fun isSystemUid(uid: Int): Boolean = uid % 100_000 == android.os.Process.SYSTEM_UID

    fun confirmPending(context: Context) {
        if (SessionInstallState.read(context)?.pending != true) return
        val confirmation = SessionInstallState.confirmation(context) ?: return
        try {
            context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: Exception) {
            Log.e("DiPlay-Update", "session confirmation launch failed; can retry or cancel", failure)
        }
    }

    /** Runs off the UI thread; unlock only after PackageInstaller accepts the abandonment. */
    fun cancelPending(context: Context) {
        val state = SessionInstallState.read(context)?.takeIf { it.pending } ?: return
        try {
            context.packageManager.packageInstaller.abandonSession(state.id)
            SessionInstallState.result(context, state.id, PackageInstaller.STATUS_FAILURE_ABORTED)
            UpdateAvailability.recordInstall(context, System.currentTimeMillis(), "session id=${state.id} cancelled")
        } catch (failure: Exception) {
            Log.w("DiPlay-Update", "session cancellation failed", failure)
            SessionInstallState.reconcile(context)
        }
    }

    /** System callers must explicitly request replacement; preserve all other session flags. */
    internal fun allowReplacement(params: PackageInstaller.SessionParams) {
        val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
            .apply { isAccessible = true }
        field.setInt(params, field.getInt(params) or INSTALL_REPLACE_EXISTING)
        check(field.getInt(params) and INSTALL_REPLACE_EXISTING != 0) {
            "Session replacement flag was not applied"
        }
    }

    @Synchronized
    fun install(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        var sessionId: Int? = null
        var committed = false
        return try {
            if (SessionInstallState.reconcile(context)?.pending == true) return true
            require(apk.isFile && apk.length() > 0) { "Missing update APK" }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())
            // Hidden API, used only by the system-app path. Fail before creating a session if unavailable.
            allowReplacement(params)
            val id = installer.createSession(params)
            sessionId = id
            SessionInstallState.begin(context, id)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // PackageInstaller supplies status extras, so this explicit PendingIntent must be mutable.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, UpdateInstallResultReceiver::class.java)
                        .setAction("${context.packageName}.UPDATE_INSTALL_RESULT"),
                    flags,
                )
                UpdateAvailability.recordInstall(context, System.currentTimeMillis(), "session requested id=$id")
                session.commit(callback.intentSender)
                committed = true
            }
            Log.i("DiPlay-Update", "session committed id=$id")
            true // Submission only; final result arrives at the manifest receiver.
        } catch (failure: Exception) {
            if (committed) {
                Log.w("DiPlay-Update", "session committed but handle cleanup failed id=$sessionId", failure)
                return true
            }
            sessionId?.let {
                runCatching { installer.abandonSession(it) }
                SessionInstallState.result(context, it, PackageInstaller.STATUS_FAILURE)
            }
            Log.e("DiPlay-Update", "session submission failed", failure)
            UpdateAvailability.recordInstall(context, System.currentTimeMillis(), "session failed=${failure.javaClass.simpleName}")
            false
        }
    }
}

/** Manifest receiver survives the Activity closing and receives asynchronous installation results. */
class UpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.UPDATE_INSTALL_RESULT") return
        val pending = goAsync()
        Thread({
            try {
                handleResult(context.applicationContext, intent)
            } catch (failure: Exception) {
                Log.e("DiPlay-Update", "session result handling failed", failure)
            } finally {
                pending?.finish()
            }
        }, "diplay-update-result").start()
    }

    internal fun handleResult(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.UPDATE_INSTALL_RESULT") return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (!SessionInstallState.result(context, id, status)) return
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            .replace(Regex("[\\r\\n]+"), " ").take(512)
        Log.i("DiPlay-Update", "session result status=$status message=$message")
        UpdateAvailability.recordInstall(context, System.currentTimeMillis(), "session status=$status $message")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            try {
                checkNotNull(confirmation) { "Missing installation confirmation" }
                // Persist before launching: Android may silently suppress background activity starts.
                SessionInstallState.saveConfirmation(context, id, confirmation)
                SessionUpdateInstaller.confirmPending(context)
            } catch (failure: Exception) {
                Log.e("DiPlay-Update", "session confirmation failed", failure)
                SessionInstallState.result(context, id, PackageInstaller.STATUS_FAILURE)
                runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                showFailure(context)
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            showFailure(context)
        }
    }

    private fun showFailure(context: Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }
}
