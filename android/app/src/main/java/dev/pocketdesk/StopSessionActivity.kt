package dev.pocketdesk

import android.app.Activity
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.pocketdesk.service.RemoteService
import dev.pocketdesk.session.SessionHub

/**
 * Target of the notification's Stop action.
 *
 * Android does not allow a dialog inside a notification, so this translucent
 * activity exists purely to ask "are you sure?" before dropping the session.
 */
class StopSessionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SessionHub.isLive) {
            finish()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_title)
            .setMessage(getString(R.string.stop_message, SessionHub.target))
            .setPositiveButton(R.string.stop_confirm) { _, _ ->
                SessionHub.stop(getString(R.string.notification_stopped))
                RemoteService.stop(this)
                finish()
            }
            .setNegativeButton(R.string.stop_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
