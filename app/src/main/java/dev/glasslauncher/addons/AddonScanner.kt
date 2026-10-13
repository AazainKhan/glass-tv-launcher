package dev.glasslauncher.addons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.glasslauncher.addon.AddonApi
import java.security.MessageDigest

/** The PackageManager side of discovery: turns installed services into [AddonCandidate]s for [AddonDiscovery]. */
internal object AddonScanner {
    /** All add-on services, or those of [pkg] only (the re-check right before a bind). */
    @Suppress("DEPRECATION")
    fun scan(context: Context, pkg: String? = null): List<AddonInfo> {
        val pm = context.packageManager
        // Only trust the permission if Glass itself declares it: another app that got there first could have defined it.
        val owner = try { pm.getPermissionInfo(AddonApi.PERMISSION, 0).packageName } catch (_: PackageManager.NameNotFoundException) { null }
        if (owner != context.packageName) return emptyList()
        val intent = Intent(AddonApi.ACTION).apply { if (pkg != null) setPackage(pkg) }
        val found = try { pm.queryIntentServices(intent, PackageManager.GET_META_DATA) } catch (_: RuntimeException) { emptyList() }
        val certs = HashMap<String, String>()
        return AddonDiscovery.filter(found.mapNotNull { ri ->
            val s = ri.serviceInfo ?: return@mapNotNull null
            val meta = s.metaData
            AddonCandidate(
                s.packageName, s.name, s.exported, s.permission,
                meta?.keySet()?.associateWith { meta.get(it)?.toString() } ?: emptyMap(),
                certs.getOrPut(s.packageName) { cert(pm, s.packageName) ?: return@mapNotNull null },
            )
        })
    }

    @Suppress("DEPRECATION")
    private fun cert(pm: PackageManager, pkg: String): String? = try {
        pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            ?.sorted()?.joinToString(",")?.takeIf { it.isNotEmpty() }
    } catch (_: PackageManager.NameNotFoundException) { null }
}
