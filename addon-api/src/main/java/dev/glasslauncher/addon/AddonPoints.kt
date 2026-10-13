package dev.glasslauncher.addon

import kotlinx.serialization.Serializable

/**
 * What Glass sends with every provider call. [values] are the user's current settings for this add-on (stored by Glass,
 * keyed by field id): this is how an add-on reads them back. [id] names a form field for actions.
 */
@Serializable
data class ProviderRequest(val api: Int = AddonApi.LEVEL, val id: String? = null, val values: Map<String, String> = emptyMap())

/**
 * A Top Shelf title. Glass fetches [image] and [logo] itself (https URLs; art no larger than 1280 px on the long side),
 * so an add-on never sends bitmaps. [link] is an https or market URI opened on Select.
 */
@Serializable
data class FeedItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val image: String? = null,
    val logo: String? = null,
    val link: String? = null,
)

/** Top Shelf feed: at most 30 items. [refreshMinutes] is how often Glass may ask again (at least 15; 0 = Glass's default). */
@Serializable
data class FeedReply(
    val api: Int = AddonApi.LEVEL,
    val heading: String = "",
    val items: List<FeedItem> = emptyList(),
    val refreshMinutes: Int = 0,
    val error: String? = null,
)

/**
 * A card in a Home row. Select opens [link] (https or market), or [component] (an activity of the add-on's own package,
 * "pkg/cls" or just "cls"), or, with neither, launches the add-on's app.
 */
@Serializable
data class RowItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val link: String? = null,
    val component: String? = null,
)

@Serializable
data class HomeRowReply(
    val api: Int = AddonApi.LEVEL,
    val title: String = "",
    val items: List<RowItem> = emptyList(),
    val refreshMinutes: Int = 0,
    val error: String? = null,
)

@Serializable data class Choice(val value: String, val label: String)

/** Show a field only while another field's value equals [equals]. [field] must itself have no rule (depth 1). Glass evaluates it locally. */
@Serializable data class ShowIf(val field: String, val equals: String)

/**
 * [type] is toggle ("true"/"false"), choice (one of [choices]), text, or action (a button).
 * [showIf]: the field is visible only while another field has a given value (evaluated by Glass from the stored values).
 * [optionsFrom] (choice only): the id of another field; the options come from a `getFieldOptions` call (request `id` = this field) that
 * Glass repeats only when that field's value changes. [choices] is what shows until then (it may be empty).
 */
@Serializable
data class FormField(
    val id: String,
    val type: String,
    val label: String,
    val help: String? = null,
    val choices: List<Choice> = emptyList(),
    val default: String? = null,
    val showIf: ShowIf? = null,
    val optionsFrom: String? = null,
)

/** Reply to `getFieldOptions`: at most 8 options. */
@Serializable data class OptionsReply(val api: Int = AddonApi.LEVEL, val choices: List<Choice> = emptyList(), val error: String? = null)

@Serializable
data class FormReply(val api: Int = AddonApi.LEVEL, val title: String = "", val fields: List<FormField> = emptyList(), val error: String? = null)

/** Reply to a form action: [message] is shown briefly by Glass. */
@Serializable
data class ActionReply(val message: String? = null, val error: String? = null)

/** The add-on's side of the provider points, without Android types. Return null for a point you do not supply. */
interface AddonPointsHandler {
    fun feed(req: ProviderRequest): FeedReply? = null
    fun homeRow(req: ProviderRequest): HomeRowReply? = null
    fun settingsForm(req: ProviderRequest): FormReply? = null
    fun onSettingsAction(req: ProviderRequest): ActionReply? = null
    /** Options for the choice field named by `req.id`, given the current values in `req.values` (a dynamic form). */
    fun fieldOptions(req: ProviderRequest): OptionsReply? = null
}

object AddonProviderServer {
    /** Null = refused, unsupported method or unsupported point. An exception in the add-on becomes an error reply. */
    fun handle(method: String, request: String?, h: AddonPointsHandler, permitted: Boolean): String? {
        if (!permitted) return null
        val req = AddonCodec.decodeProviderRequest(request) ?: ProviderRequest()
        return try {
            when (method) {
                AddonApi.METHOD_FEED -> h.feed(req)?.let(AddonCodec::encodeFeed)
                AddonApi.METHOD_HOME_ROW -> h.homeRow(req)?.let(AddonCodec::encodeHomeRow)
                AddonApi.METHOD_SETTINGS_FORM -> h.settingsForm(req)?.let(AddonCodec::encodeForm)
                AddonApi.METHOD_SETTINGS_ACTION -> h.onSettingsAction(req)?.let(AddonCodec::encodeAction)
                AddonApi.METHOD_FIELD_OPTIONS -> h.fieldOptions(req)?.let(AddonCodec::encodeOptions)
                else -> null
            }
        } catch (e: Exception) {
            val msg = e.javaClass.simpleName
            when (method) {
                AddonApi.METHOD_FEED -> AddonCodec.encodeFeed(FeedReply(error = msg))
                AddonApi.METHOD_HOME_ROW -> AddonCodec.encodeHomeRow(HomeRowReply(error = msg))
                AddonApi.METHOD_SETTINGS_FORM -> AddonCodec.encodeForm(FormReply(error = msg))
                AddonApi.METHOD_SETTINGS_ACTION -> AddonCodec.encodeAction(ActionReply(error = msg))
                AddonApi.METHOD_FIELD_OPTIONS -> AddonCodec.encodeOptions(OptionsReply(error = msg))
                else -> null
            }
        }
    }
}
