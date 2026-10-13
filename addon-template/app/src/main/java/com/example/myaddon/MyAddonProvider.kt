package com.example.myaddon

import dev.glasslauncher.addon.ActionReply
import dev.glasslauncher.addon.Choice
import dev.glasslauncher.addon.FeedItem
import dev.glasslauncher.addon.FeedReply
import dev.glasslauncher.addon.FormField
import dev.glasslauncher.addon.FormReply
import dev.glasslauncher.addon.GlassAddonProvider
import dev.glasslauncher.addon.HomeRowReply
import dev.glasslauncher.addon.ProviderRequest
import dev.glasslauncher.addon.OptionsReply
import dev.glasslauncher.addon.RowItem
import dev.glasslauncher.addon.ShowIf

/**
 * Top Shelf feed, Home row and Settings form. You supply data; Glass draws it, fetches the images itself and builds every Intent.
 * Every request carries the user's current settings in [ProviderRequest.values], keyed by field id.
 */
class MyAddonProvider : GlassAddonProvider() {
    // Images: https only, a public host name, at most 1280 px on the long side, JPEG, PNG or static WebP, under 2 MB.
    private fun art(n: Int) = "https://example.com/art/$n.jpg"

    override fun feed(req: ProviderRequest) = FeedReply(
        heading = "Picks for ${req.values["nickname"].orEmpty().ifEmpty { "you" }}",
        refreshMinutes = 30, // never under 15; 0 = Glass's default (30)
        items = (1..5).map { FeedItem(id = "item$it", title = "Title $it", subtitle = "A subtitle", description = "A short description.", image = art(it), link = "https://example.com/title/$it") },
    )

    override fun homeRow(req: ProviderRequest) = HomeRowReply(
        title = "My Row",
        refreshMinutes = 30,
        // link: https or market://details?id=<package>. component: an activity of THIS package. Neither: Glass launches this app.
        items = (1..4).map { RowItem(id = "card$it", title = "Card $it", image = art(it), link = "https://example.com/card/$it") },
    )

    override fun settingsForm(req: ProviderRequest) = FormReply(
        title = "My Add-on",
        fields = listOf(
            FormField("bright", "toggle", "Bright mode", default = "false"),
            FormField("flavour", "choice", "Flavour", choices = listOf(Choice("vanilla", "Vanilla"), Choice("mocha", "Mocha")), default = "vanilla"),
            // Dynamic forms: this field shows only while "Bright mode" is on; "topping" gets its options from fieldOptions() below,
            // asked again only when the flavour changes.
            FormField("topping", "choice", "Topping", optionsFrom = "flavour"),
            FormField("nickname", "text", "Nickname", showIf = ShowIf("bright", "true")),
            FormField("reset", "action", "Reset counter"),
        ),
    )

    override fun fieldOptions(req: ProviderRequest): OptionsReply? =
        if (req.id == "topping") OptionsReply(choices = if (req.values["flavour"] == "mocha") listOf(Choice("cocoa", "Cocoa")) else listOf(Choice("nuts", "Nuts"))) else null

    override fun onSettingsAction(req: ProviderRequest): ActionReply {
        if (req.id == "reset") {
            context?.getSharedPreferences("my-addon", android.content.Context.MODE_PRIVATE)?.edit()?.putInt("count", 0)?.apply()
            return ActionReply(message = "Counter reset")
        }
        return ActionReply(error = "unknown action")
    }
}
