package dev.pocketdesk

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies Material You colours before any activity inflates its theme.
 *
 * On Android 12+ the palette comes from the wallpaper; below that (and on the
 * legacy channel) the static seed colours in `themes.xml` are used.
 */
class PocketDeskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
