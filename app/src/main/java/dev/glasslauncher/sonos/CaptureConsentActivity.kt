package dev.glasslauncher.sonos

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * The consent step of a Sonos route: RECORD_AUDIO, then the MediaProjection dialog. The plain APK asks both every
 * session. Glass Pro and Glass OS pre-grant them (P135, [ProjectionPregrant], glass-system-service.sh), so the system
 * approves without a dialog; this activity is unchanged. SonosConsentPolicyTest limits where that grant may live. It runs in its own task (taskAffinity "", see the manifest) and
 * ends with finishAndRemoveTask(), so the app that was in front returns instead of Glass Home.
 */
class CaptureConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun askProjection() =
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQ_PROJECTION)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) askProjection()
        else { SonosRoutes.onDenied(this); finishAndRemoveTask() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == REQ_PROJECTION) SonosRoutes.onConsent(this, resultCode, data)
        finishAndRemoveTask()
    }

    private companion object { const val REQ_MIC = 1; const val REQ_PROJECTION = 2 }
}
