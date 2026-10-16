package dev.glasslauncher.featured

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * How a Top Shelf item is launched (P140): the intent the app published for the row (which is what resumes playback; the
 * position lives in the app), else the title link, else the app itself. This file decides which intents are safe to start:
 * a row is data from another app, so it may only ever reach that app. Pure parts are unit tested.
 */
object ResumeIntents {
    sealed interface Step {
        /** The row's own `intent:` URI, restricted to [pkg]. */
        data class Published(val uri: String, val pkg: String) : Step
        /** VIEW of a plain link, sent to [pkg]. */
        data class View(val uri: String, val pkg: String) : Step
        data class Launch(val pkg: String) : Step
    }

    /** What a validated `intent:` URI said (everything else is refused). */
    data class Parsed(val data: String, val action: String?, val component: String?, val pkg: String?)

    private val SCHEME = Regex("[a-z][a-z0-9+.-]{0,31}")
    private val BLOCKED_SCHEMES = setOf("file", "content", "javascript", "intent", "android-app", "data", "about", "blob", "filesystem")
    private const val VIEW = "android.intent.action.VIEW"
    private const val MAIN = "android.intent.action.MAIN"

    /** A link scheme that may be opened: http(s), market, or the app's own custom scheme (it only reaches the app through setPackage). */
    fun schemeOk(scheme: String?): Boolean = scheme != null && SCHEME.matches(scheme) && scheme !in BLOCKED_SCHEMES

    /** The scheme of a plain URI string, lower case. */
    fun schemeOf(uri: String): String? = uri.substringBefore(':', "").lowercase().takeIf { it.isNotEmpty() && ':' in uri }

    /**
     * Parses and checks an `intent:` URI published for an app whose package is [itemPkg]; null when it is not safe: a selector,
     * a package or component of another app (or Glass), an action outside VIEW / MAIN / the app's own, or a blocked scheme.
     */
    fun parsePublished(uri: String, itemPkg: String, glassPkg: String): Parsed? {
        if (itemPkg == glassPkg || !uri.startsWith("intent:", ignoreCase = true)) return null
        val body = uri.substring("intent:".length)
        val marker = body.indexOf("#Intent;")
        if (marker < 0) return null
        val data = body.substring(0, marker)
        var scheme: String? = null; var action: String? = null; var component: String? = null; var pkg: String? = null
        for (field in body.substring(marker + "#Intent;".length).split(';')) {
            when {
                field == "end" -> break
                field == "SEL" -> return null
                field.startsWith("scheme=") -> scheme = field.substringAfter('=')
                field.startsWith("action=") -> action = field.substringAfter('=')
                field.startsWith("component=") -> component = field.substringAfter('=')
                field.startsWith("package=") -> pkg = field.substringAfter('=')
            }
        }
        if (pkg != null && pkg != itemPkg) return null
        if (component != null && component.substringBefore('/') != itemPkg) return null
        if (action != null && action != VIEW && action != MAIN && !action.startsWith("$itemPkg.")) return null
        if (action == MAIN && component == null) return null
        // The scheme is the field, else the data's own ("intent:https://…" is allowed by parseUri).
        val effective = (scheme ?: schemeOf(data))?.lowercase()
        if (data.isNotEmpty() || scheme != null) { if (!schemeOk(effective)) return null }
        else if (component == null) return null   // nothing to open
        return Parsed(data, action, component, pkg)
    }

    /**
     * The ordered attempts for [item]: its own published intent (for TV rows), then its title link per capable app, then launching the app.
     * [installed] are the item's packages that are installed, in order. Untrusted rows ([FeaturedItem.id] "tv:…") never produce an unrestricted step.
     */
    fun steps(item: FeaturedItem, installed: List<String>, glassPkg: String): List<Step> {
        // A row belongs to the app that published it: only that app is ever tried. Scanning the installed apps is for items with no owner.
        val owner = item.owner
        val apps = (if (owner != null) installed.filter { it == owner } else installed).filter { it != glassPkg }
        val link = item.link
        val out = mutableListOf<Step>()
        if (link != null) {
            if (link.startsWith("intent:", ignoreCase = true)) {
                for (pkg in apps) {
                    val parsed = parsePublished(link, pkg, glassPkg) ?: continue
                    // Without a package or component the intent names no app: it needs a known owner to be sent anywhere.
                    if (owner == null && parsed.pkg == null && parsed.component == null) continue
                    out += Step.Published(link, pkg)
                }
            } else if (schemeOk(schemeOf(link))) {
                for (pkg in apps) out += Step.View(link, pkg)
            }
        }
        for (pkg in apps) out += Step.Launch(pkg)
        return out
    }

    /**
     * Stremio (P170): `stremio:///detail/<type>/<id>/<videoId>?autoPlay=true` starts that video directly, with no stream list, so Resume
     * needs no key. [link] is the row's title link or its `intent:` URI; returns the VIEW link with autoPlay set when it names an episode
     * or movie (`<type>/<id>/<videoId>`), else null (a title page without a video id has nothing to play).
     */
    fun stremioAutoPlay(link: String?): String? {
        if (link == null) return null
        val uri = if (link.startsWith("intent:", ignoreCase = true)) {
            val body = link.substring("intent:".length)
            body.substringBefore("#Intent;").takeIf { it.isNotEmpty() }
                ?: Regex("(?:^|;)S\\.?data=([^;]+)").find(body)?.groupValues?.get(1) ?: return null
        } else link
        if (!uri.startsWith("stremio:", ignoreCase = true)) return null
        val path = uri.substringAfter("stremio:").trimStart('/').substringBefore('#')
        val (route, query) = path.substringBefore('?') to path.substringAfter('?', "")
        val parts = route.split('/').filter { it.isNotEmpty() }
        if (parts.size < 4 || parts[0] != "detail") return null
        val keep = query.split('&').filter { it.isNotEmpty() && !it.startsWith("autoPlay=") }
        return "stremio:///" + parts.joinToString("/") + "?" + (keep + "autoPlay=true").joinToString("&")
    }

    /** Whether Glass may also offer this link to any app (only for links Glass fetched itself, never a row another app published). */
    fun isAppPublished(item: FeaturedItem): Boolean = item.owner != null || item.id.startsWith("tv:")

    // ---- Android side ----

    /** The intent for a step, forced into the app; null if it cannot run. */
    fun intentFor(context: Context, step: Step): Intent? {
        val pm = context.packageManager
        val intent = when (step) {
            is Step.Published -> runCatching { Intent.parseUri(step.uri, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                selector = null
                flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
                clipData = null
                setPackage(step.pkg)
            }
            is Step.View -> Intent(Intent.ACTION_VIEW, Uri.parse(step.uri)).setPackage(step.pkg)
            is Step.Launch -> return (pm.getLeanbackLaunchIntentForPackage(step.pkg) ?: pm.getLaunchIntentForPackage(step.pkg))
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { it.resolveActivity(pm) != null }
    }

    fun start(context: Context, step: Step): Boolean {
        val intent = intentFor(context, step) ?: return false
        return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }
}
