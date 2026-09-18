package com.github.umer0586.droidpad.miditests

import com.github.umer0586.droidpad.data.util.midi.MidiStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun List<ByteArray>.hex() = map { message ->
    message.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}

@RunWith(JUnit4::class)
class MidiStreamParserTest {

    @Test
    fun `a whole message in one chunk comes back as one message`() {
        val parser = MidiStreamParser()

        assertEquals(listOf("90 3c 7f"), parser.accept(bytes(0x90, 0x3C, 0x7F)).hex())
    }

    @Test
    fun `several messages in one chunk are split apart`() {
        val parser = MidiStreamParser()

        val messages = parser.accept(bytes(0x90, 0x3C, 0x7F, 0x80, 0x3C, 0x00, 0xB0, 0x07, 0x40))

        assertEquals(listOf("90 3c 7f", "80 3c 00", "b0 07 40"), messages.hex())
    }

    @Test
    fun `a message split across chunks is only emitted once complete`() {
        val parser = MidiStreamParser()

        assertTrue(parser.accept(bytes(0x90)).isEmpty())
        assertTrue(parser.accept(bytes(0x3C)).isEmpty())
        assertEquals(listOf("90 3c 7f"), parser.accept(bytes(0x7F)).hex())
    }

    @Test
    fun `a two byte message is complete without a third byte`() {
        val parser = MidiStreamParser()

        assertEquals(listOf("c1 05"), parser.accept(bytes(0xC1, 0x05)).hex())
    }

    @Test
    fun `running status repeats the previous status byte`() {
        val parser = MidiStreamParser()

        // one note on, then two more carried by running status
        val messages = parser.accept(bytes(0x90, 0x3C, 0x7F, 0x3E, 0x7F, 0x40, 0x7F))

        assertEquals(listOf("90 3c 7f", "90 3e 7f", "90 40 7f"), messages.hex())
    }

    @Test
    fun `a realtime byte inside a message does not disturb it`() {
        val parser = MidiStreamParser()

        // 0xF8 is the clock a DAW sends 24 times a beat, and it is legal
        // anywhere, including between the data bytes of another message
        val messages = parser.accept(bytes(0x90, 0x3C, 0xF8, 0x7F))

        assertEquals(listOf("90 3c 7f"), messages.hex())
    }

    @Test
    fun `realtime bytes do not clear running status`() {
        val parser = MidiStreamParser()

        val messages = parser.accept(bytes(0x90, 0x3C, 0x7F, 0xFE, 0x3E, 0x7F))

        assertEquals(listOf("90 3c 7f", "90 3e 7f"), messages.hex())
    }

    @Test
    fun `a system exclusive block is skipped whole`() {
        val parser = MidiStreamParser()

        val messages = parser.accept(
            bytes(0xF0, 0x7E, 0x00, 0x06, 0x01, 0xF7, 0x90, 0x3C, 0x7F)
        )

        assertEquals(listOf("90 3c 7f"), messages.hex())
    }

    @Test
    fun `a system common message clears running status`() {
        val parser = MidiStreamParser()

        parser.accept(bytes(0x90, 0x3C, 0x7F))
        // 0xF1 quarter frame, after which a bare data byte is not a note on
        val messages = parser.accept(bytes(0xF1, 0x00, 0x3E, 0x7F))

        assertTrue("stray data bytes should be dropped, got ${messages.hex()}", messages.isEmpty())
    }

    @Test
    fun `only the requested slice of the buffer is read`() {
        val parser = MidiStreamParser()

        val buffer = bytes(0xFF, 0xFF, 0x90, 0x3C, 0x7F, 0xFF)

        assertEquals(listOf("90 3c 7f"), parser.accept(buffer, offset = 2, count = 3).hex())
    }

    @Test
    fun `a stray data byte before any status byte is dropped`() {
        val parser = MidiStreamParser()

        assertTrue(parser.accept(bytes(0x3C, 0x7F)).isEmpty())
        assertEquals(listOf("90 3c 7f"), parser.accept(bytes(0x90, 0x3C, 0x7F)).hex())
    }
}
