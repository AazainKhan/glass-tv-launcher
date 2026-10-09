package dev.glasslauncher.featured

/**
 * The shape of the full-screen shelf's cards. A row takes one shape from its art: square covers when most
 * of it is square (Spotify's playlists and albums), 16:9 otherwise. An item that doesn't fit a square row
 * (Spotify's 16:9 free-tier banner among the covers) is left out rather than cropped.
 */
object CardShape {
    fun square(items: List<FeaturedItem>): Boolean = items.isNotEmpty() && items.count { isSquare(it) } * 2 > items.size

    fun uniform(items: List<FeaturedItem>): List<FeaturedItem> = if (square(items)) items.filter(::isSquare) else items

    /** TvContract's poster aspect codes (16:9, 3:2, 4:3, 1:1, 2:3, movie poster) as width / height. */
    fun fromTvContract(code: Int): Float? = when (code) {
        0 -> 16 / 9f
        1 -> 3 / 2f
        2 -> 4 / 3f
        3 -> 1f
        4 -> 2 / 3f
        5 -> 1 / 1.441f
        else -> null
    }

    private fun isSquare(item: FeaturedItem) = item.aspect?.let { kotlin.math.abs(it - 1f) < 0.05f } == true
}
