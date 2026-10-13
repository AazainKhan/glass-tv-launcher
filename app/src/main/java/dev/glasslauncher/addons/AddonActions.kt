package dev.glasslauncher.addons

/** A remote-button action of an add-on, as stored in the button mapping: `addon:<package>/<service>/<action id>`. It survives the add-on being absent. */
data class AddonActionRef(val pkg: String, val service: String, val id: String) {
    val key get() = "addon:$pkg/$service/$id"
}

object AddonActionId {
    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    /** A Java class name as `Class.getName()` gives it: dotted identifiers, `$` for a nested class; no leading, trailing or doubled dots. */
    private val CLASS = Regex("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*")
    private val ID = Regex("[A-Za-z0-9_.-]{1,40}")

    /** Null for anything that is not exactly three valid parts (a malformed stored key is simply not an add-on action). */
    fun decode(key: String): AddonActionRef? {
        if (!key.startsWith("addon:")) return null
        val parts = key.removePrefix("addon:").split('/')
        if (parts.size != 3) return null
        val (pkg, service, id) = parts
        return if (PKG.matches(pkg) && service.length <= 200 && CLASS.matches(service) && ID.matches(id)) AddonActionRef(pkg, service, id) else null
    }
}

/** What an add-on offers, as last read. */
data class ActionListing(val service: String, val title: String, val actions: List<dev.glasslauncher.addon.ActionInfo>)

data class PickerEntry(val key: String, val label: String, val available: Boolean)

/** The "Add-on actions" part of the button picker and the names the buttons page shows. */
object AddonActionPicker {
    /** "<action> · via <add-on>": the attribution is kept whole, the action name is what gets cut. */
    private fun label(name: String, addon: String): String {
        val suffix = " · via $addon"
        return AddonText.clean(name, (60 - suffix.length).coerceAtLeast(1)) + suffix
    }

    /** One entry per offered action (by package, then label), plus, if [current] is an add-on action that is not offered, an "unavailable" entry for it. */
    fun entries(lists: Map<String, ActionListing>, current: String?): List<PickerEntry> {
        val offered = lists.toSortedMap().flatMap { (pkg, l) ->
            l.actions.map { PickerEntry(AddonActionRef(pkg, l.service, it.id).key, label(it.label, l.title), true) }
        }
        val missing = current?.let { AddonActionId.decode(it) }?.takeIf { c -> offered.none { it.key == c.key } }
        return offered + listOfNotNull(missing?.let { PickerEntry(it.key, "${it.id} · ${it.pkg} (unavailable)", false) })
    }

    /** The name a mapped button shows: the action's label and add-on, or "unavailable" when the add-on is not known right now. */
    fun name(ref: AddonActionRef, lists: Map<String, ActionListing>): String =
        entries(lists, ref.key).firstOrNull { it.key == ref.key }?.label ?: "${ref.id} (unavailable)"
}
