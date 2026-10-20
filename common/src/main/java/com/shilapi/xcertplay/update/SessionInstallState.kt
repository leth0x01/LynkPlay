package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageInstaller

/** Durable identity of the one self-update session owned by this process/user. */
internal object SessionInstallState {
    private const val PREFS = "update-install-session"
    data class State(val id: Int, val status: Int, val confirmationAvailable: Boolean = false) {
        val pending: Boolean get() = status == PackageInstaller.STATUS_PENDING_USER_ACTION
    }

    fun read(context: Context): State? {
        // Snapshot reads never wait on the writer's disk commit or PackageInstaller binder calls.
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val id = values["id"] as? Int ?: return null
        return State(id, values["status"] as? Int ?: PackageInstaller.STATUS_FAILURE, values["confirmation"] is String)
    }

    @Synchronized
    fun begin(context: Context, id: Int) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("id", id).putInt("status", PackageInstaller.STATUS_PENDING_USER_ACTION)
            .remove("confirmation")
            .putLong("installedAt", context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime).commit()) {
            "Cannot persist installation session"
        }
    }

    /** Old callbacks must not release the lock or overwrite the outcome of a newer session. */
    @Synchronized
    fun result(context: Context, id: Int, status: Int): Boolean {
        val current = read(context) ?: return false
        if (current.id != id || (!current.pending && status == PackageInstaller.STATUS_PENDING_USER_ACTION)) return false
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("status", status)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) edit.remove("confirmation")
        return edit.commit()
    }

    @Synchronized
    fun saveConfirmation(context: Context, id: Int, intent: android.content.Intent) {
        if (read(context)?.let { it.id == id && it.pending } != true) return
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("confirmation", intent.toUri(0)).commit()) { "Cannot persist installation confirmation" }
    }

    fun confirmation(context: Context): android.content.Intent? {
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("confirmation", null) ?: return null
        return runCatching { android.content.Intent.parseUri(uri, 0) }.getOrNull()
    }

    /** A replaced process may not have a compatible result receiver in the newly installed APK. */
    fun reconcile(context: Context): State? {
        val state = read(context) ?: return null
        if (state.pending && context.packageManager.packageInstaller.mySessions.none { it.sessionId == state.id }) {
            val installedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("installedAt", 0)
            val changed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime != installedAt
            val status = if (changed) PackageInstaller.STATUS_SUCCESS else PackageInstaller.STATUS_FAILURE
            result(context, state.id, status)
            UpdateAvailability.recordInstall(context, System.currentTimeMillis(), "session id=${state.id} ended without callback; packageChanged=$changed")
        }
        return read(context)
    }
}
