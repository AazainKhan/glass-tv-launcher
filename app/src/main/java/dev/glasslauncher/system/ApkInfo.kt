package dev.glasslauncher.system

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

/** How a replacement APK's signing key relates to the installed app of the same package. */
enum class SignerCheck { NotInstalled, Same, Changed, Unknown }

/** What the confirm card shows. [signers] are hex SHA-256 of the APK's certificates, newest first. */
data class ApkInfo(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val signers: List<String>,
    val check: SignerCheck,
) {
    val signerChanged get() = check == SignerCheck.Changed
    val signerShort get() = signers.firstOrNull()?.take(16).orEmpty()
}

object ApkInfoReader {
    /** Null when [file] isn't a readable APK. Android calls only; the comparison is [compare]. */
    fun read(context: Context, file: File): ApkInfo? {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull() ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = file.path; app.publicSourceDir = file.path // else loadLabel finds no resources
        val signers = info.signingInfo?.let(::certs).orEmpty()
        val installed: List<String>? = try {
            pm.getPackageInfo(info.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.let(::certs)
        } catch (_: PackageManager.NameNotFoundException) { emptyList() }
        return ApkInfo(
            pkg = info.packageName,
            label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            sizeBytes = file.length(),
            signers = signers,
            check = compare(signers, installed),
        )
    }

    // The history carries rotated keys (oldest first), so an update to a rotated key still matches.
    private fun certs(s: android.content.pm.SigningInfo): List<String> =
        (if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory)
            .orEmpty().map { sha256(it) }.reversed()

    private fun sha256(s: Signature) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** [installed] null = couldn't be read, empty = not installed. Any shared certificate keeps the same owner. */
    internal fun compare(new: List<String>, installed: List<String>?): SignerCheck = when {
        installed == null -> SignerCheck.Unknown
        installed.isEmpty() -> SignerCheck.NotInstalled
        new.isEmpty() -> SignerCheck.Unknown
        new.any { it in installed } -> SignerCheck.Same
        else -> SignerCheck.Changed
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "${bytes shr 10} KB"
        else -> "$bytes B"
    }
}
