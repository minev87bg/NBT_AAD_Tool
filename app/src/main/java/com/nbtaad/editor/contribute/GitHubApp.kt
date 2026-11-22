package com.nbtaad.editor.contribute

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The GitHub app: where a person writes on an issue without logs. Posting there needs a GitHub account, and the app is the
 * easy way to use it from a phone. The app is opened on the page itself; when it is not on the phone, the person is shown
 * where to get it (the store), or can carry on in the browser.
 */
object GitHubApp {
    const val PACKAGE = "com.github.android"
    const val STORE_URI = "market://details?id=$PACKAGE"
    const val STORE_WEB = "https://play.google.com/store/apps/details?id=$PACKAGE"

    enum class Outcome {
        /** The app was opened on the page. */
        OPENED,

        /** The app is not on this phone. */
        NOT_INSTALLED,

        /** The app is there but would not take the link: the browser is the way. */
        WOULD_NOT_OPEN,
    }

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Opens [url] (an issue or pull request page) in the GitHub app. */
    fun open(ctx: Context, url: String): Outcome {
        if (!installed(ctx)) return Outcome.NOT_INSTALLED
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Outcome.OPENED
        } catch (_: Exception) {
            Outcome.WOULD_NOT_OPEN
        }
    }

    /** The app's page in the store; the store's web page when the phone has no store app. */
    fun openStore(ctx: Context) {
        for (link in listOf(STORE_URI, STORE_WEB)) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // try the next way
            }
        }
    }
}
