package dev.glasslauncher.addons

import dev.glasslauncher.addon.AddonCodec
import dev.glasslauncher.addon.FeedItem
import dev.glasslauncher.addon.FeedReply
import dev.glasslauncher.addon.FormField
import dev.glasslauncher.addon.FormReply
import dev.glasslauncher.addon.ShowIf
import dev.glasslauncher.addon.HomeRowReply
import dev.glasslauncher.addon.RowItem
import dev.glasslauncher.featured.FeaturedFeed
import dev.glasslauncher.featured.FeaturedItem

/** Links an add-on may put on Select: https and market only, no credentials in the address, no whitespace or control characters. */
object AddonLinks {
    private const val MAX = 512
    private val HTTPS = Regex("^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?([/?#][^\\s]*)?$")
    /** The Play Store page of one package, nothing else (no search, no other market forms). */
    private val MARKET = Regex("^market://details\\?id=[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun link(url: String?): String? = url?.takeIf { it.length <= MAX && it.none { c -> c.isISOControl() || c.isWhitespace() } && (HTTPS.matches(it) || MARKET.matches(it)) }

    /** Art URLs: https only, a public host name (never localhost, a local-network name or an IP literal); Glass fetches them itself, see [AddonArt]. */
    fun image(url: String?): String? = url?.takeIf {
        it.length <= MAX && it.none { c -> c.isISOControl() || c.isWhitespace() } && HTTPS.matches(it) &&
            !AddonHosts.blockedName(it.removePrefix("https://").takeWhile { c -> c != '/' && c != '?' && c != '#' && c != ':' })
    }
}

/** What Select on an add-on card may do. Glass builds the Intent itself from this; it never takes an Intent from an add-on. */
sealed interface AddonAction {
    data class View(val uri: String) : AddonAction
    /** An activity of the add-on's own package. */
    data class Component(val pkg: String, val cls: String) : AddonAction
    /** The add-on's own launcher entry. */
    data object LaunchApp : AddonAction
}

object AddonIntents {
    private val CLASS = Regex("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*")

    /**
     * A link wins; else an explicit component, which must be in [pkg] itself (never another app, never Glass); else the app
     * itself. A present but invalid link or component is refused (null), not turned into something else.
     */
    fun resolve(pkg: String, glassPkg: String, link: String?, component: String?): AddonAction? {
        if (pkg == glassPkg) return null
        if (link != null) return AddonLinks.link(link)?.let { AddonAction.View(it) }
        if (component != null) {
            val (p, c) = if ('/' in component) component.substringBefore('/') to component.substringAfter('/') else pkg to component
            val cls = if (c.startsWith(".")) pkg + c else c
            if (p != pkg || !CLASS.matches(cls) || cls.length > 200) return null
            return AddonAction.Component(pkg, cls)
        }
        return AddonAction.LaunchApp
    }
}

/** The parts of the config the add-on points need: which add-ons are on (package -> cert) and their settings (`pkg/field` -> value). */
data class AddonSnapshot(val enabled: Map<String, String>, val settings: Map<String, String>) {
    fun valuesOf(pkg: String): Map<String, String> = settings.filterKeys { it.startsWith("$pkg/") }.mapKeys { it.key.removePrefix("$pkg/") }
    companion object { val NONE = AddonSnapshot(emptyMap(), emptyMap()) }
}

/** How often an add-on's content may be asked for again: its own hint, never under 15 minutes, 30 when it gives none. */
object AddonRefresh {
    const val MIN = 15
    const val DEFAULT = 30
    const val MAX = 24 * 60
    fun minutes(declared: Int): Int = if (declared <= 0) DEFAULT else declared.coerceIn(MIN, MAX)
}

/** Skips a refresh of [key] until its interval has passed or the [version] it was read for has changed (a pure schedule, fake-clock tested). */
class IntervalGate(private val now: () -> Long) {
    private class Mark(val version: Any?, val until: Long)
    private val marks = HashMap<String, Mark>()
    @Synchronized fun due(key: String, version: Any?): Boolean = marks[key]?.let { it.version != version || now() >= it.until } ?: true
    @Synchronized fun mark(key: String, version: Any?, minutes: Int) { marks[key] = Mark(version, now() + AddonRefresh.minutes(minutes) * 60_000L) }
    /** A retry sooner than the normal interval, for a failed read (the 15-minute floor is for successful content, not for outages). */
    @Synchronized fun retryIn(key: String, version: Any?, ms: Long) { marks[key] = Mark(version, now() + ms) }
    @Synchronized fun forget(key: String) { marks.remove(key) }
}

/**
 * Whether a focus event may start a refresh: Home must be visible, the key's 15-minute floor (or an explicit user change, see
 * [AddonSurfaces.version]) must have passed, and none may already be running for it. Focus moving around never polls: it only
 * asks this, and the answer is no until the floor is over.
 */
class FocusRefresh(private val gate: IntervalGate) {
    private val running = HashSet<String>()
    @Synchronized fun begin(key: String, version: Any?, homeVisible: Boolean): Boolean {
        if (!homeVisible || key in running || !gate.due(key, version)) return false
        running += key
        return true
    }
    @Synchronized fun end(key: String) { running -= key }
}

/** What an add-on's content looks like once Glass has made it safe to draw. */
data class SafeRow(val title: String, val items: List<SafeCard>, val refreshMinutes: Int)
data class SafeCard(val id: String, val title: String, val subtitle: String?, val image: String?, val action: AddonAction?)
data class SafeForm(val title: String, val fields: List<FormField>)

object AddonContent {
    const val MAX_ITEMS = 30
    const val MAX_FIELDS = 12
    const val MAX_CHOICES = 8
    const val MAX_VALUE = 80
    private val ID = Regex("[A-Za-z0-9_.-]{1,40}")
    private val TYPES = setOf("toggle", "choice", "text", "action")

    /** "<heading> · via <add-on>": the attribution is kept whole and the heading is what gets cut. */
    fun via(heading: String, addon: String): String {
        val suffix = " · via $addon"
        return AddonText.clean(heading, (48 - suffix.length).coerceAtLeast(1)) + suffix
    }

    /** A Top Shelf feed in Glass's own types: bounded, cleaned, art URLs https only, headed "… · via <add-on>". Null for junk or an error reply. */
    fun feed(json: String?, pkg: String, addon: String): Pair<FeaturedFeed, Int>? {
        val r: FeedReply = AddonCodec.decodeFeed(json)?.takeIf { it.error == null } ?: return null
        val items = r.items.asSequence().filter { ID.matches(it.id) }.distinctBy { it.id }.mapNotNull { feedItem(it, pkg) }.take(MAX_ITEMS).toList()
        if (items.isEmpty()) return null
        return FeaturedFeed(via(AddonText.clean(r.heading, 32).ifEmpty { addon }, addon), items) to r.refreshMinutes
    }

    private fun feedItem(i: FeedItem, pkg: String): FeaturedItem? {
        val title = AddonText.clean(i.title, 60).ifEmpty { return null }
        return FeaturedItem(
            id = "addon:$pkg/${i.id}", title = title,
            subtitle = AddonText.clean(i.subtitle, 80).ifEmpty { null },
            description = AddonText.clean(i.description, 280).ifEmpty { null },
            image = AddonLinks.image(i.image), logo = AddonLinks.image(i.logo),
            link = AddonLinks.link(i.link),
            // Select tries the link, then launches the add-on's own app: never another package.
            packages = listOf(pkg),
        )
    }

    fun row(json: String?, pkg: String, glassPkg: String, addon: String): SafeRow? {
        val r: HomeRowReply = AddonCodec.decodeHomeRow(json)?.takeIf { it.error == null } ?: return null
        val cards = r.items.asSequence().filter { ID.matches(it.id) }.distinctBy { it.id }.mapNotNull { card(it, pkg, glassPkg) }.take(MAX_ITEMS).toList()
        if (cards.isEmpty()) return null
        return SafeRow(via(AddonText.clean(r.title, 32).ifEmpty { addon }, addon), cards, r.refreshMinutes)
    }

    private fun card(i: RowItem, pkg: String, glassPkg: String): SafeCard? {
        val title = AddonText.clean(i.title, 40).ifEmpty { return null }
        // A card whose link or component is invalid is dropped, not turned into a launch of the app.
        val action = AddonIntents.resolve(pkg, glassPkg, i.link, i.component) ?: return null
        return SafeCard(i.id, title, AddonText.clean(i.subtitle, 60).ifEmpty { null }, AddonLinks.image(i.image), action)
    }

    private val RULE_TYPES = setOf("toggle", "choice", "text")

    fun form(json: String?, addon: String): SafeForm? {
        val r: FormReply = AddonCodec.decodeForm(json)?.takeIf { it.error == null } ?: return null
        val first = r.fields.asSequence().filter { ID.matches(it.id) && it.type in TYPES }.distinctBy { it.id }.mapNotNull { f ->
            val label = AddonText.clean(f.label, 40).ifEmpty { return@mapNotNull null }
            val choices = cleanChoices(f.choices)
            val dynamic = f.type == "choice" && f.optionsFrom != null
            if (f.type == "choice" && choices.isEmpty() && !dynamic) return@mapNotNull null
            // A dynamic choice's default can only be checked against its static options.
            val default = f.default?.let { AddonText.clean(it, MAX_VALUE) }?.takeIf { d ->
                when (f.type) { "toggle" -> d == "true" || d == "false"; "choice" -> choices.any { it.value == d }; "text" -> true; else -> false }
            }
            val show = f.showIf?.let { ShowIf(it.field, AddonText.clean(it.equals, MAX_VALUE)) }
            f.copy(label = label, help = AddonText.clean(f.help, 120).ifEmpty { null }, choices = if (f.type == "choice") choices else emptyList(), default = default,
                showIf = show, optionsFrom = if (dynamic) f.optionsFrom else null)
        }.take(MAX_FIELDS).toList()
        // Rules are checked against the fields that survived: a field with a broken rule is dropped, never shown unconditionally.
        // Depth is 1: a rule may only point at a field that has no rule itself; nothing is ever evaluated as code.
        val byId = first.associateBy { it.id }
        val fields = first.filter { f ->
            val s = f.showIf
            val o = f.optionsFrom
            val r1 = s == null || (s.field != f.id && s.equals.isNotEmpty() && byId[s.field]?.let { it.type in RULE_TYPES && it.showIf == null } == true)
            val r2 = o == null || (f.type == "choice" && o != f.id && byId[o]?.type in RULE_TYPES)
            val noRawOptionsFrom = f.type == "choice" || f.optionsFrom == null
            r1 && r2 && noRawOptionsFrom
        }
        return SafeForm(AddonText.clean(r.title, 32).ifEmpty { addon }, fields)
    }

    private fun cleanChoices(list: List<dev.glasslauncher.addon.Choice>) = list.take(MAX_CHOICES).mapNotNull { c ->
        val v = AddonText.clean(c.value, MAX_VALUE).ifEmpty { return@mapNotNull null }
        c.copy(value = v, label = AddonText.clean(c.label, 30).ifEmpty { v })
    }.distinctBy { it.value }

    /** The options of a dynamic choice field: cleaned and capped; null for junk, an error reply or no usable option (the static ones stay). */
    fun options(json: String?): List<dev.glasslauncher.addon.Choice>? =
        AddonCodec.decodeOptions(json)?.takeIf { it.error == null }?.let { cleanChoices(it.choices) }?.takeIf { it.isNotEmpty() }

    fun action(json: String?): String? = AddonCodec.decodeAction(json)?.takeIf { it.error == null }?.let { AddonText.clean(it.message, 80) }

    /** A value the user typed or chose, kept within what Glass stores for an add-on setting. */
    fun value(v: String) = AddonText.clean(v, MAX_VALUE)
}

/** Stored add-on settings (`pkg/field`) that no longer belong to anything: an uninstalled add-on's, and fields it stopped declaring. */
object AddonPurge {
    private fun pkgOf(key: String) = key.substringBeforeLast('/')
    private fun fieldOf(key: String) = key.substringAfterLast('/')

    fun uninstalled(settings: Map<String, String>, installed: (String) -> Boolean): Set<String> =
        settings.keys.filter { '/' in it && !installed(pkgOf(it)) }.toSet()

    fun undeclared(settings: Map<String, String>, pkg: String, declared: Set<String>): Set<String> =
        settings.keys.filter { '/' in it && pkgOf(it) == pkg && fieldOf(it) !in declared }.toSet()
}

/** Evaluating a dynamic form from the values Glass stores: no code from the add-on is ever run. */
object FormRules {
    /** The value a field has: what the user set, else its default, else "false" for a toggle. */
    fun valueOf(f: FormField, stored: Map<String, String>): String = stored[f.id] ?: f.default ?: if (f.type == "toggle") "false" else ""

    /** The fields to draw now: those without a rule, and those whose rule holds. Hidden fields keep their stored values. */
    fun visible(fields: List<FormField>, stored: Map<String, String>): List<FormField> = fields.filter { f ->
        val rule = f.showIf
        rule == null || fields.firstOrNull { it.id == rule.field }?.let { valueOf(it, stored) == rule.equals } == true
    }

    /** Dynamic choices that depend on [changed]: their stored value no longer fits the new options, so Glass forgets it (they fall back to the default). */
    fun dependents(fields: List<FormField>, changed: String): List<String> = fields.filter { it.optionsFrom == changed }.map { it.id }

    /** The value after [current] in [choices] (wrapping); the first when [current] is not among them. */
    fun next(choices: List<dev.glasslauncher.addon.Choice>, current: String): String =
        choices[(choices.indexOfFirst { it.value == current } + 1) % choices.size].value
}

/** Options already read, by (field, value of the field they depend on): a dependency that did not change is never asked again. */
class OptionsCache(private val max: Int = 32) {
    private val map = object : LinkedHashMap<Pair<String, String>, List<dev.glasslauncher.addon.Choice>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Pair<String, String>, List<dev.glasslauncher.addon.Choice>>) = size > max
    }
    @Synchronized fun get(field: String, dep: String) = map[field to dep]
    @Synchronized fun put(field: String, dep: String, options: List<dev.glasslauncher.addon.Choice>) { map[field to dep] = options }
}
