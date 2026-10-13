package dev.glasslauncher.sonos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteSupportTest {
    @Test fun hdmiAloneCannotMuteLocally() = assertFalse(MuteSupport.supported(volumeFixed = false, hdmiOut = true, bluetoothOut = false))
    @Test fun bluetoothSpeakerCanMute() = assertTrue(MuteSupport.supported(volumeFixed = false, hdmiOut = true, bluetoothOut = true))
    @Test fun builtInSpeakerCanMute() = assertTrue(MuteSupport.supported(volumeFixed = false, hdmiOut = false, bluetoothOut = false))
    @Test fun globallyFixedVolumeNeverMutes() = assertFalse(MuteSupport.supported(volumeFixed = true, hdmiOut = false, bluetoothOut = true))
}
