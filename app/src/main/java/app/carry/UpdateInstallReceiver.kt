package app.carry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * PackageInstaller delivers the result here. The confirm screen has to be started
 * from this broadcast, the same way NetSky finishes an update on a device that
 * will not open an APK link by itself.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmation(intent) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
                .onFailure { toast(context, "This phone would not open the installer.") }
            return
        }
        toast(context, installResult(status, detail))
    }

    private fun confirmation(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

internal fun installResult(status: Int, detail: String): String {
    val mismatch = detail.contains("signature", ignoreCase = true) ||
        detail.contains("conflict", ignoreCase = true) ||
        status == PackageInstaller.STATUS_FAILURE_CONFLICT
    return when {
        status == PackageInstaller.STATUS_SUCCESS -> "Carry updated."
        mismatch -> "The signatures do not match. Uninstall Carry once, then install this build."
        status == PackageInstaller.STATUS_FAILURE_STORAGE -> "This phone does not have room for the update."
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
        status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The phone blocked the install. Allow apps from this source, then press Update again."
        status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This phone cannot install that build."
        detail.isNotBlank() -> detail
        else -> "The update did not install."
    }
}
