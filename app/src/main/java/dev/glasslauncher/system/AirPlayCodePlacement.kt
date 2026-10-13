package dev.glasslauncher.system

/** Where AirPlay's pairing code is shown. Pure, so the rule is testable. */
object AirPlayCodePlacement {
    enum class Where {
        /** No code. */
        None,
        /** Glass's own card in Home (also the fallback while another app is in front and the banner service is off: it waits there). */
        GlassCard,
        /** A text banner over whatever is in front, from the Remote Buttons accessibility service. */
        Banner,
        /** Glass is in front but a text field is open: the card waits behind it so it can't take the keyboard's focus. */
        Queued,
    }

    fun decide(code: String?, glassInFront: Boolean, textInputUp: Boolean, bannerService: Boolean): Where = when {
        code == null -> Where.None
        glassInFront -> if (textInputUp) Where.Queued else Where.GlassCard
        bannerService -> Where.Banner
        else -> Where.GlassCard
    }

    fun bannerText(code: String) = "AirPlay code $code"
}
