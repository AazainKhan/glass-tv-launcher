package dev.glasslauncher.system

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/** How a replacement APK's signing key relates to the installed app of the same package. */
enum class SignerCheck { NotInstalled, Same, Changed, Unknown }

/** New app, or how the file's versionCode compares with the installed one. */
enum class VersionChange { NewApp, Upgrade, Same, Downgrade }

/** Hex SHA-256 of an app's signing certificates: [current] is who signs it now, [lineage] adds rotated-away keys. */
data class SignerSet(val current: Set<String>, val lineage: Set<String> = current)

/** What the confirm card shows. [signers] are the APK's current certificate hashes. */
data class ApkInfo(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val signers: List<String>,
    val check: SignerCheck,
    val installedVersionCode: Long? = null,
    val installedSigner: String? = null,
) {
    val signerChanged get() = check == SignerCheck.Changed
    val signerShort get() = signers.firstOrNull()?.take(16).orEmpty()
    val version get() = ApkInfoReader.versionChange(versionCode, installedVersionCode)
}

object ApkInfoReader {
    /** Null when [file] isn't a readable APK. Android calls only; the comparisons are [compare] and [versionChange]. */
    fun read(context: Context, file: File): ApkInfo? {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull() ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = file.path; app.publicSourceDir = file.path // else loadLabel finds no resources
        val new = info.signingInfo?.let(::certs)
        val installedInfo = try {
            pm.getPackageInfo(info.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) { null }
        val installed = installedInfo?.signingInfo?.let(::certs)
        return ApkInfo(
            pkg = info.packageName,
            label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            sizeBytes = file.length(),
            signers = new?.current?.toList().orEmpty(),
            check = compare(new, installed, isInstalled = installedInfo != null),
            installedVersionCode = installedInfo?.longVersionCode,
            installedSigner = installed?.current?.firstOrNull(),
        )
    }

    // The history carries rotated keys (oldest first), so an update to a rotated key still matches.
    private fun certs(s: SigningInfo): SignerSet {
        val history: List<Signature> = s.signingCertificateHistory?.toList().orEmpty()
        val current: List<Signature> = if (s.hasMultipleSigners()) s.apkContentsSigners?.toList().orEmpty() else history.takeLast(1)
        return SignerSet(current.map(::sha256).toSet(), (if (s.hasMultipleSigners()) current else history).map(::sha256).toSet())
    }

    private fun sha256(s: Signature) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Same only if every signer matches (or the new lineage contains all of the installed signers: a key rotation). */
    internal fun compare(new: SignerSet?, installed: SignerSet?, isInstalled: Boolean = installed != null): SignerCheck = when {
        !isInstalled -> SignerCheck.NotInstalled
        new == null || installed == null || new.current.isEmpty() || installed.current.isEmpty() -> SignerCheck.Unknown
        new.current == installed.current || new.lineage.containsAll(installed.current) -> SignerCheck.Same
        else -> SignerCheck.Changed
    }

    internal fun versionChange(code: Long, installed: Long?) = when {
        installed == null -> VersionChange.NewApp
        code > installed -> VersionChange.Upgrade
        code == installed -> VersionChange.Same
        else -> VersionChange.Downgrade
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "${bytes shr 10} KB"
        else -> "$bytes B"
    }
}

/** A private copy of a downloaded APK, hashed once: the card shows this copy and install uses exactly it. */
class StagedApk(val file: File, val sha256: String, val info: ApkInfo) {
    fun discard() { file.delete() }

    companion object {
        fun hash(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return ApkInfoReader.hex(md.digest())
        }

        /** Copies [source] into Glass's private dir and reads everything from the copy; null if it isn't an APK. */
        fun stage(context: Context, source: File): StagedApk? {
            val dir = File(context.filesDir, "installs").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val copy = File(dir, "pending.apk")
            source.inputStream().use { i -> copy.outputStream().use { i.copyTo(it) } }
            val info = ApkInfoReader.read(context, copy)
            if (info == null) { copy.delete(); return null }
            return StagedApk(copy, hash(copy), info)
        }
    }
}
