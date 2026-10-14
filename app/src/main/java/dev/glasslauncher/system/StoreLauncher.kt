package dev.glasslauncher.system

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The app store without root (P168): Glass opens the store's own page for an app, or the store itself, through public links. Fire's Appstore
 * answers `amzn://apps/android?p=<package>` (an app's page) and `?s=<query>` (a search); Google Play and every other Android TV store answer
 * `market://`. The Amazon launcher's own store entry only bounces a request back to Home, which with Glass as Home never shows the store (AmazonStore, root),
 * so these links are the way in on a plain install. Ordering is pure and tested; [open] tries each until one resolves to another app.
 */
object StoreLauncher {
    private const val PLAY = "com.android.vending"

    /** The links for one app's page, the store this TV most likely has first. */
    fun appLinks(isFire: Boolean, pkg: String): List<String> =
        if (isFire) listOf("amzn://apps/android?p=$pkg", "market://details?id=$pkg") else listOf("market://details?id=$pkg", "amzn://apps/android?p=$pkg")

    /** The links that open the store itself (a search for nothing: its front page or an empty search). */
    fun homeLinks(isFire: Boolean): List<String> =
        if (isFire) listOf("amzn://apps/android?s=", "market://search?q=") else listOf("market://search?q=", "amzn://apps/android?s=")

    /** Opens the page of [pkg]'s app, or with null the store. False when nothing on this TV answers (the caller says so). */
    fun open(context: Context, pkg: String?): Boolean {
        val pm = context.packageManager
        val isFire = HomeSetup.isFireTv
        if (pkg == null) {
            // Google's store has a TV launcher entry of its own; try it before the search link.
            pm.getLeanbackLaunchIntentForPackage(PLAY)?.let { i ->
                if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
            }
        }
        for (link in if (pkg == null) homeLinks(isFire) else appLinks(isFire, pkg)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = intent.resolveActivity(pm) ?: continue
            if (target.packageName == context.packageName) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
