package com.chamelex.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyOrderingTest {

    private fun k(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `order is remembered then user groups then cracked then builtin`() {
        val remembered = listOf(k("AAAAAAAAAAAA"))
        val user1 = listOf(k("BBBBBBBBBBBB"), k("CCCCCCCCCCCC"))
        val user2 = listOf(k("DDDDDDDDDDDD"))
        val cracked = listOf(k("EEEEEEEEEEEE"))
        val builtin = listOf(k("FFFFFFFFFFFF"), k("AAAAAAAAAAAA"))
        val ordered = orderKeys(remembered, listOf(user1, user2), cracked, builtin)
        assertEquals(
            listOf("AAAAAAAAAAAA", "BBBBBBBBBBBB", "CCCCCCCCCCCC", "DDDDDDDDDDDD", "EEEEEEEEEEEE", "FFFFFFFFFFFF"),
            ordered.map { it.toHexUpper() },
        )
    }

    @Test
    fun `duplicates removed keeping first occurrence`() {
        val ordered = orderKeys(
            listOf(k("AAAAAAAAAAAA")),
            listOf(listOf(k("AAAAAAAAAAAA"), k("BBBBBBBBBBBB"))),
            listOf(k("BBBBBBBBBBBB")),
            listOf(k("AAAAAAAAAAAA")),
        )
        assertEquals(listOf("AAAAAAAAAAAA", "BBBBBBBBBBBB"), ordered.map { it.toHexUpper() })
    }

    @Test
    fun `empty remembered works`() {
        val ordered = orderKeys(emptyList(), emptyList(), emptyList(), listOf(k("FFFFFFFFFFFF")))
        assertEquals(listOf("FFFFFFFFFFFF"), ordered.map { it.toHexUpper() })
    }

    @Test
    fun `card keys pack and unpack round trip`() {
        val sectors = listOf(
            k("111111111111") to k("222222222222"),
            k("333333333333") to null,
            null to k("444444444444"),
            null to null,
        )
        val packed = packCardKeys(sectors)
        assertEquals(48, packed.size)
        val unpacked = unpackCardKeys(packed)
        assertEquals(4, unpacked.size)
        assertEquals("111111111111", unpacked[0].first?.toHexUpper())
        assertEquals("222222222222", unpacked[0].second?.toHexUpper())
        assertEquals("333333333333", unpacked[1].first?.toHexUpper())
        assertNull(unpacked[1].second)
        assertNull(unpacked[2].first)
        assertEquals("444444444444", unpacked[2].second?.toHexUpper())
        assertNull(unpacked[3].first)
        assertNull(unpacked[3].second)
    }
}
