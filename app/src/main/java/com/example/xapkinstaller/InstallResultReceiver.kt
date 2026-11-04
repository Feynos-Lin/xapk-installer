package com.example.xapkinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface InstallState {
    data object Idle : InstallState
    data class Working(val message: String) : InstallState
    data class Ready(val manifest: XapkManifest) : InstallState
    data class Done(val manifest: XapkManifest?) : InstallState
    data class Failed(val message: String) : InstallState
}

object InstallStatus {
    val state = MutableStateFlow<InstallState>(InstallState.Idle)
    var current: XapkManifest? = null
}

/**
 * Receives progress from PackageInstaller. When Android needs the user to confirm,
 * it sends STATUS_PENDING_USER_ACTION with the confirmation screen to launch.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS ->
                InstallStatus.state.value = InstallState.Done(InstallStatus.current)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                InstallStatus.state.value = InstallState.Failed("Install was cancelled.")
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                InstallStatus.state.value = InstallState.Failed(friendlyError(detail))
            }
        }
    }

    private fun friendlyError(detail: String): String = when {
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in detail || "signatures do not match" in detail ->
            "The installed version of this app was signed with a different key. Uninstall it first, then try again."
        "INSTALL_FAILED_VERSION_DOWNGRADE" in detail ->
            "A newer version of this app is already installed."
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" in detail ->
            "Not enough storage space."
        "INSTALL_FAILED_NO_MATCHING_ABIS" in detail ->
            "This app isn't built for your phone's processor."
        "INSTALL_FAILED_OLDER_SDK" in detail ->
            "This app needs a newer version of Android."
        else -> "Install failed: $detail"
    }
}
