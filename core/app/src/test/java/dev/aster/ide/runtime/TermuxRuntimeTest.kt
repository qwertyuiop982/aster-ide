package dev.aster.ide.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxRuntimeTest {
    @Test fun acceptsThePortAnnouncedAfterEphemeralBind() {
        assertEquals(41237, parseBridgePort("Aster bridge ready on loopback port 41237"))
    }

    @Test fun rejectsMalformedOrUnboundPortAnnouncements() {
        assertNull(parseBridgePort("Aster bridge ready on loopback port 0"))
        assertNull(parseBridgePort("Aster bridge ready on loopback port invalid"))
        assertNull(parseBridgePort("bridge starting"))
    }
}
