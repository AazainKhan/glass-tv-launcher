package dev.glasslauncher.addons

import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.AddonCodec
import dev.glasslauncher.addon.AddonRequest
import dev.glasslauncher.addon.StateReply
import dev.glasslauncher.addon.TileState
import dev.glasslauncher.addon.TilesReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonCodecTest {
    private val tile = TileState("counter", "Counter", "add", on = true, value = "3", enabled = false)

    @Test fun requestRoundTrips() {
        assertEquals(AddonRequest(tile = "x"), AddonCodec.decodeRequest(AddonCodec.encodeRequest(AddonRequest(tile = "x"))))
        assertEquals(AddonRequest(), AddonCodec.decodeRequest(AddonCodec.encodeRequest(AddonRequest())))
    }

    @Test fun tilesAndStateRoundTrip() {
        val tiles = TilesReply(tiles = listOf(tile, TileState("b", "B")))
        assertEquals(tiles, AddonCodec.decodeTiles(AddonCodec.encodeTiles(tiles)))
        assertEquals(StateReply(tile), AddonCodec.decodeState(AddonCodec.encodeState(StateReply(tile))))
        assertEquals(StateReply(null), AddonCodec.decodeState(AddonCodec.encodeState(StateReply(null))))
    }

    @Test fun unknownFieldsAreIgnored() {
        val json = """{"api":2,"future":{"x":1},"tiles":[{"id":"a","label":"A","size":"2x1","badge":[1,2]}]}"""
        val r = AddonCodec.decodeTiles(json)!!
        assertEquals(2, r.api)
        assertEquals(TileState("a", "A"), r.tiles.single())
    }

    @Test fun missingFieldsTakeDefaults() {
        assertEquals(TilesReply(api = AddonApi.LEVEL), AddonCodec.decodeTiles("{}"))
        val t = AddonCodec.decodeState("""{"tile":{"id":"a","label":"A"}}""")!!.tile!!
        assertEquals(TileState("a", "A", "", false, null, true), t)
    }

    @Test fun malformedDecodesToNull() {
        assertNull(AddonCodec.decodeTiles(null))
        assertNull(AddonCodec.decodeTiles("not json"))
        assertNull(AddonCodec.decodeTiles("""{"tiles":[{"label":"no id"}]}"""))
        assertNull(AddonCodec.decodeState("""{"tile":5}"""))
    }

    @Test fun gateUsesApiLevelAndMinGlassApi() {
        assertEquals(true, AddonApi.compatible(1, 1, glassLevel = 1))
        assertEquals(false, AddonApi.compatible(0, 1, glassLevel = 1))
        assertEquals(false, AddonApi.compatible(1, 2, glassLevel = 1)) // needs a newer Glass
        assertEquals(true, AddonApi.compatible(2, 1, glassLevel = 3))  // last two levels
        assertEquals(false, AddonApi.compatible(1, 1, glassLevel = 3)) // too old
        assertEquals(true, AddonApi.compatible(5, 3, glassLevel = 3))  // newer add-on that still works with us
    }
}
