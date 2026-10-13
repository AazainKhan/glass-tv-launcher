package dev.glasslauncher.addon

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Base class for an add-on's Top Shelf, Home row and Settings page. Declare it exported with
 * `android:permission="dev.glasslauncher.permission.BIND_ADDON"` (so only Glass can reach it) and name its authority
 * in the add-on service's [AddonApi.META_PROVIDER] metadata. It has no data: only [call] is used.
 */
abstract class GlassAddonProvider : ContentProvider(), AddonPointsHandler {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Defence in depth next to android:permission.
        val permitted = context?.checkCallingPermission(AddonApi.PERMISSION) == PackageManager.PERMISSION_GRANTED
        val out = AddonProviderServer.handle(method, arg, this, permitted) ?: return null
        return Bundle().apply { putString(AddonApi.KEY_JSON, out) }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
