package com.shilapi.xcertplay.update

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File

/** Only called after the driver requests installation; never asks for new ADB authorization. */
internal object AdbUpdateInstaller {
    fun install(context: Context, verifiedApk: File): Boolean = runCatching {
        if (!verifiedApk.isFile || verifiedApk.length() == 0L) return false
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return false
            // Stream the private verified APK, not the mutable public Downloads copy.
            // -S avoids needing EOF and works without making private storage shell-readable.
            // A successful self-update normally kills this process before the command returns.
            UpdateAvailability.recordInstall(context, System.currentTimeMillis(), "adb requested")
            val output = verifiedApk.inputStream().use {
                adb.shellStream("pm install -r -S ${verifiedApk.length()}", 30_000, it)
            }
            val success = output?.lineSequence()?.any { it.trim() == "Success" } == true
            Log.i("DiPlay-Update", "ADB install success=$success output=$output")
            success
        }
    }.getOrElse {
        Log.w("DiPlay-Update", "ADB install unavailable; falling back to installer", it)
        false
    }
}
