package com.chamelex.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceSettingsTest {

    @Test
    fun `animation mode codes round trip`() {
        assertEquals(AnimationMode.FULL, AnimationMode.fromCode(0))
        assertEquals(AnimationMode.MINIMAL, AnimationMode.fromCode(1))
        assertEquals(AnimationMode.NONE, AnimationMode.fromCode(2))
        assertEquals(AnimationMode.SYMMETRIC, AnimationMode.fromCode(3))
        assertNull(AnimationMode.fromCode(4))
    }

    @Test
    fun `button type codes are ascii`() {
        assertEquals('A'.code, ButtonType.A.code)
        assertEquals('B'.code, ButtonType.B.code)
        assertEquals(ButtonType.A, ButtonType.fromCode(0x41))
        assertEquals(ButtonType.B, ButtonType.fromCode(0x42))
    }

    @Test
    fun `button press function codes`() {
        assertEquals(ButtonPressFunction.NONE, ButtonPressFunction.fromCode(0))
        assertEquals(ButtonPressFunction.NEXT_SLOT, ButtonPressFunction.fromCode(1))
        assertEquals(ButtonPressFunction.PREV_SLOT, ButtonPressFunction.fromCode(2))
        assertEquals(ButtonPressFunction.CLONE, ButtonPressFunction.fromCode(3))
        assertEquals(ButtonPressFunction.BATTERY, ButtonPressFunction.fromCode(4))
        assertEquals(ButtonPressFunction.FIELD_GEN, ButtonPressFunction.fromCode(5))
        assertNull(ButtonPressFunction.fromCode(6))
    }

    @Test
    fun `mfc key type codes`() {
        assertEquals(0x60, MfcKeyType.A.code)
        assertEquals(0x61, MfcKeyType.B.code)
        assertEquals(MfcKeyType.A, MfcKeyType.fromCode(0x60))
        assertEquals(MfcKeyType.B, MfcKeyType.fromCode(0x61))
    }
}
