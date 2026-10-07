package dev.glasslauncher.apps

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class IconPack(val packageName: String, val label: String)

/** Reads ADW/Nova-style icon packs (appfilter.xml). */
class IconPacks(private val context: Context) {

    private var loadedPack: String? = null
    private var mapping: Map<String, String> = emptyMap()
    private var resources: Resources? = null

    fun installed(): List<IconPack> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, IconPack>()
        for (action in listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")) {
            for (info in pm.queryIntentActivities(Intent(action), 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg !in seen) seen[pkg] = IconPack(pkg, info.loadLabel(pm).toString())
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    @Synchronized
    fun iconFor(pack: String, component: ComponentName): Drawable? {
        if (pack != loadedPack) load(pack)
        val res = resources ?: return null
        val name = mapping["ComponentInfo{${component.packageName}/${component.className}}"]
            ?: mapping.entries.firstOrNull { it.key.startsWith("ComponentInfo{${component.packageName}/") }?.value
            ?: return null
        @SuppressLint("DiscouragedApi")
        val id = res.getIdentifier(name, "drawable", pack)
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    private fun load(pack: String) {
        loadedPack = pack
        mapping = emptyMap()
        resources = runCatching { context.packageManager.getResourcesForApplication(pack) }.getOrNull() ?: return
        mapping = runCatching { parse(pack, resources!!) }.getOrDefault(emptyMap())
    }

    @SuppressLint("DiscouragedApi")
    private fun parse(pack: String, res: Resources): Map<String, String> {
        val parser: XmlPullParser = res.getIdentifier("appfilter", "xml", pack)
            .takeIf { it != 0 }
            ?.let { res.getXml(it) }
            ?: XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(res.assets.open("appfilter.xml"), "UTF-8")
            }
        val result = HashMap<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component") ?: continue
                val drawable = parser.getAttributeValue(null, "drawable") ?: continue
                result[component] = drawable
            }
        }
        return result
    }
}
