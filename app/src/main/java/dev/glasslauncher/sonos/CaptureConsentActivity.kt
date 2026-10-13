package dev.glasslauncher.sonos

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * The consent step of a Sonos route: RECORD_AUDIO, then the MediaProjection dialog. Asked every session in every
 * install mode; nothing here pre-grants or skips either, with or without root (SonosConsentPolicyTest checks that
 * no script, module or source grants RECORD_AUDIO).
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
        else { SonosRoutes.onDenied(this); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == REQ_PROJECTION) SonosRoutes.onConsent(this, resultCode, data)
        finish()
    }

    private companion object { const val REQ_MIC = 1; const val REQ_PROJECTION = 2 }
}
