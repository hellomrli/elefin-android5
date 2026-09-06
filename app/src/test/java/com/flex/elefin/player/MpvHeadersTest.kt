package com.flex.elefin.player

import com.flex.elefin.player.mpv.toMpvHeaderOption
import org.junit.Assert.assertEquals
import org.junit.Test

class MpvHeadersTest {
    @Test fun authorizationCommasAreNotTreatedAsHeaderSeparators() {
        assertEquals("X-Emby-Authorization: Client=Elefin\\, Token=test,Accept: */*",
            toMpvHeaderOption("X-Emby-Authorization: Client=Elefin, Token=test\r\nAccept: */*\r\n"))
    }

    @Test fun aSingleTokenHeaderIsUnchanged() {
        assertEquals("X-Emby-Token: token", toMpvHeaderOption("X-Emby-Token: token"))
        assertEquals("", toMpvHeaderOption("\r\n"))
    }
}
