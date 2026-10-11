package dev.glasslauncher.system

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fire OS enforces the privapp-permissions allowlist at boot (ro.control_privapp_permissions=enforce): a privileged
 * permission that Glass requests and the allowlist lacks stops system_server from booting (P75 B review). The
 * allowlist is generated from the requested permissions, so every one the manifest declares must come out of the
 * generator, protected ones especially.
 */
class PrivappAllowlistTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val requested = Regex("""<uses-permission[^>]*?android:name="([^"]+)"[^>]*?/>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(manifest).map { it.groupValues[1] }.toList()
    /** Permissions the manifest marks as protected (tools:ignore="ProtectedPermissions"): signature|privileged ones. */
    private val protected = Regex("""<uses-permission[^>]*?android:name="([^"]+)"[^>]*?ProtectedPermissions[^>]*?/>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(manifest).map { it.groupValues[1] }.toList()

    private fun listed(xml: String) = Regex("""permission name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    @Test fun everyRequestedPermissionIsInTheGeneratedAllowlist() {
        val out = listed(RootFeatures.privappXml("dev.glasslauncher", requested))
        val missing = requested.filter { it !in out }
        assertTrue("requested but not allowlisted: $missing", missing.isEmpty())
    }

    @Test fun theProtectedOnesAreThere() {
        assertTrue("the manifest should declare protected permissions", protected.isNotEmpty())
        assertTrue("BLUETOOTH_PRIVILEGED is declared", "android.permission.BLUETOOTH_PRIVILEGED" in protected)
        val out = listed(RootFeatures.privappXml("dev.glasslauncher", requested))
        assertTrue("protected but not allowlisted: ${protected.filter { it !in out }}", protected.all { it in out })
    }

    @Test fun aPermissionTheDeviceDoesNotDefineIsLeftOut() {
        val out = listed(RootFeatures.privappXml("dev.glasslauncher", requested) { it != "android.permission.BLUETOOTH_SCAN" })
        assertTrue("BLUETOOTH_SCAN is requested", "android.permission.BLUETOOTH_SCAN" in requested)
        assertTrue("unknown here: not listed", "android.permission.BLUETOOTH_SCAN" !in out)
        assertTrue("the rest still is", "android.permission.BLUETOOTH_PRIVILEGED" in out)
    }

    @Test fun theTvProviderExtrasAreAdded() {
        val out = listed(RootFeatures.privappXml("dev.glasslauncher", emptyList()))
        assertTrue("ACCESS_ALL_EPG_DATA comes from the extras: $out", "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA" in out)
    }

    /** P75 B stick finding: a new privileged permission is only granted once the /data update is dropped after a refresh. */
    @Test fun theModuleServiceDropsTheDataUpdateOnceAfterARefresh() {
        val svc = File("src/main/assets/glass-system-service.sh").readText()
        assertTrue("guarded by the flag", "uninstall-updates" in svc && "[ -f \"\$FLAG\" ] || exit 0" in svc)
        assertTrue("waits for boot", "sys.boot_completed" in svc)
        assertTrue("clears the flag before acting (no loop)", svc.indexOf("rm -f \"\$FLAG\"") < svc.indexOf("uninstall-system-updates"))
        assertTrue("brings Home back", "android.intent.category.HOME" in svc)
        val check = File("../scripts/privapp-check").readText()
        assertTrue("privapp-check installs the same script and sets the flag", "glass-system-service.sh" in check && "touch \$MOD/uninstall-updates" in check)
        assertTrue("privapp-check uses the SDK's tools, not PATH", "cmdline-tools" in check && "sdk.dir" in check)
    }
}
