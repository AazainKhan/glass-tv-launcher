package dev.glasslauncher.data

import dev.glasslauncher.system.RemoteButtons
import kotlinx.serialization.Serializable

/**
 * A saved set of button mappings (P233): what each button of a controller or remote does, plus a controller's stick tuning. [buttons]
 * is by button id without the device ("a", "app1"), so a profile saved from one controller loads onto another.
 */
@Serializable
data class MappingProfile(val name: String, val buttons: Map<String, String> = emptyMap(), val sticks: ControllerSticks? = null)

/** Which kind of device a profile is for; also its key in [LauncherConfig.mappingProfiles]. */
enum class ProfileKind(val key: String) { Pad("pad"), Remote("remote") }

/** Pure edits of the saved profiles, so the save/load/delete rules are plain JVM tests. */
object MappingProfiles {
    const val MAX_NAME = 24
    const val MAX_PER_KIND = 12

    private fun idsOf(kind: ProfileKind) = when (kind) {
        ProfileKind.Pad -> RemoteButtons.pad.map { it.id.removePrefix("pad:") }
        ProfileKind.Remote -> RemoteButtons.all.map { it.id }
    }

    /** The config key of button [id] on [device] (a controller's own key, or the remote's shared one). */
    private fun keyOf(kind: ProfileKind, device: String, id: String) = if (kind == ProfileKind.Pad) RemoteButtons.padKey(device, id) else id

    fun of(cfg: LauncherConfig, kind: ProfileKind): List<MappingProfile> = cfg.mappingProfiles[kind.key].orEmpty()

    /** A name as stored: trimmed, no longer than [MAX_NAME]; null when nothing is left. */
    fun clean(name: String): String? = name.trim().take(MAX_NAME).trim().takeIf { it.isNotEmpty() }

    /** What [device] does now, as a profile called [name]. Only buttons the user changed are kept; the rest stay at their defaults. */
    fun capture(cfg: LauncherConfig, kind: ProfileKind, device: String, name: String): MappingProfile = MappingProfile(
        name,
        idsOf(kind).mapNotNull { id -> cfg.remoteButtons[keyOf(kind, device, id)]?.let { id to it } }.toMap(),
        if (kind == ProfileKind.Pad) cfg.controllerSticks[device] else null,
    )

    /** Saves the current mapping of [device] as [name], replacing a profile of that name. Null when the name is empty or the list is full. */
    fun save(cfg: LauncherConfig, kind: ProfileKind, device: String, name: String): LauncherConfig? {
        val n = clean(name) ?: return null
        val existing = of(cfg, kind)
        val replacing = existing.any { it.name.equals(n, ignoreCase = true) }
        if (!replacing && existing.size >= MAX_PER_KIND) return null
        val profile = capture(cfg, kind, device, existing.firstOrNull { it.name.equals(n, ignoreCase = true) }?.name ?: n)
        val list = if (replacing) existing.map { if (it.name.equals(n, ignoreCase = true)) profile else it } else existing + profile
        return cfg.copy(mappingProfiles = cfg.mappingProfiles + (kind.key to list), activeProfile = cfg.activeProfile + (device to profile.name))
    }

    /** Makes [device] do what [profile] says: every button of the kind is set from it (a button it doesn't list returns to its default). */
    fun load(cfg: LauncherConfig, kind: ProfileKind, device: String, profile: MappingProfile): LauncherConfig {
        val keys = idsOf(kind).map { keyOf(kind, device, it) }.toSet()
        val buttons = cfg.remoteButtons.filterKeys { it !in keys } + profile.buttons.filterKeys { it in idsOf(kind) }.mapKeys { keyOf(kind, device, it.key) }
        val sticks = if (kind == ProfileKind.Pad) cfg.controllerSticks - device + (profile.sticks?.let { mapOf(device to it) } ?: emptyMap()) else cfg.controllerSticks
        return cfg.copy(remoteButtons = buttons, controllerSticks = sticks, activeProfile = cfg.activeProfile + (device to profile.name))
    }

    fun delete(cfg: LauncherConfig, kind: ProfileKind, name: String): LauncherConfig = cfg.copy(
        mappingProfiles = cfg.mappingProfiles + (kind.key to of(cfg, kind).filterNot { it.name == name }),
        activeProfile = cfg.activeProfile.filterValues { it != name },
    )
}
