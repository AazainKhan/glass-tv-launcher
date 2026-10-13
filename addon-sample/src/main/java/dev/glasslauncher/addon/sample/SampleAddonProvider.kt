package dev.glasslauncher.addon.sample

import dev.glasslauncher.addon.ActionReply
import dev.glasslauncher.addon.Choice
import dev.glasslauncher.addon.FeedItem
import dev.glasslauncher.addon.FeedReply
import dev.glasslauncher.addon.FormField
import dev.glasslauncher.addon.FormReply
import dev.glasslauncher.addon.GlassAddonProvider
import dev.glasslauncher.addon.HomeRowReply
import dev.glasslauncher.addon.ProviderRequest
import dev.glasslauncher.addon.RowItem

/** Reference add-on content: a 5-title Top Shelf feed, a Home row and a settings form. The settings come back in every request. */
class SampleAddonProvider : GlassAddonProvider() {
    private fun art(n: Int) = "https://picsum.photos/seed/glass$n/1280/720"

    override fun feed(req: ProviderRequest): FeedReply {
        val who = req.values["nickname"].orEmpty().ifEmpty { "you" }
        val flavour = req.values["flavour"] ?: "vanilla"
        return FeedReply(
            heading = "Picks for $who",
            refreshMinutes = 30,
            items = (1..5).map {
                FeedItem(
                    id = "item$it", title = "Sample Title $it", subtitle = "$flavour pick",
                    description = "A sample title supplied by the sample add-on. Glass fetches the art itself.",
                    image = art(it), link = "https://example.com/sample/$it",
                )
            },
        )
    }

    override fun homeRow(req: ProviderRequest) = HomeRowReply(
        title = if (req.values["bright"] == "true") "Bright Picks" else "Sample Picks",
        refreshMinutes = 30,
        items = (6..9).map { RowItem(id = "card$it", title = "Card $it", subtitle = "from the sample", image = art(it), link = "https://example.com/card/$it") },
    )

    override fun settingsForm(req: ProviderRequest) = FormReply(
        title = "Sample Add-on",
        fields = listOf(
            FormField("bright", "toggle", "Bright Row Title", help = "Changes the Home row's title."),
            FormField("flavour", "choice", "Flavour", choices = listOf(Choice("vanilla", "Vanilla"), Choice("mocha", "Mocha")), default = "vanilla"),
            FormField("nickname", "text", "Nickname", help = "Shown on the Top Shelf heading."),
            FormField("reset_counter", "action", "Reset Counter"),
        ),
    )

    override fun onSettingsAction(req: ProviderRequest): ActionReply {
        if (req.id == "reset_counter") {
            context?.getSharedPreferences("sample", android.content.Context.MODE_PRIVATE)?.edit()?.putInt("count", 0)?.apply()
            return ActionReply(message = "Counter reset")
        }
        return ActionReply(error = "unknown action")
    }
}
