package com.github.umer0586.droidpad.miditests

import com.github.umer0586.droidpad.data.GaugeEvent
import com.github.umer0586.droidpad.data.GaugeProperties
import com.github.umer0586.droidpad.data.LedEvent
import com.github.umer0586.droidpad.data.SliderEvent
import com.github.umer0586.droidpad.data.SliderProperties
import com.github.umer0586.droidpad.data.SwitchEvent
import com.github.umer0586.droidpad.data.connectionconfig.MidiConfig
import com.github.umer0586.droidpad.data.connectionconfig.MidiMapping
import com.github.umer0586.droidpad.data.connectionconfig.MidiMessageType
import com.github.umer0586.droidpad.data.database.entities.ControlPadItem
import com.github.umer0586.droidpad.data.database.entities.ItemType
import com.github.umer0586.droidpad.data.util.midi.MidiDecoder
import com.github.umer0586.droidpad.ui.components.LEDSTATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private fun item(identifier: String, type: ItemType, properties: String = "{}") =
    ControlPadItem(
        itemIdentifier = identifier,
        controlPadId = 1,
        itemType = type,
        properties = properties
    )

private fun midi(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun decoder(mapping: MidiMapping, vararg items: ControlPadItem) =
    MidiDecoder(config = MidiConfig(mappings = listOf(mapping)), items = items.toList())

@RunWith(JUnit4::class)
class MidiDecoderTest {

    @Test
    fun `a control change drives the gauge it is mapped to`() {
        val properties = GaugeProperties(minValue = 0f, maxValue = 100f).toJson()
        val decoder = decoder(
            MidiMapping(target = "g", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20),
            item("g", ItemType.GAUGE, properties)
        )

        val event = GaugeEvent.fromJson(decoder.decode(midi(0xB0, 20, 127)).single())

        assertEquals("g", event.id)
        assertEquals(100f, event.value, 0.01f)
    }

    @Test
    fun `a gauge is scaled into its own range`() {
        val properties = GaugeProperties(minValue = 20f, maxValue = 40f).toJson()
        val decoder = decoder(
            MidiMapping(target = "g", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20),
            item("g", ItemType.GAUGE, properties)
        )

        assertEquals(20f, GaugeEvent.fromJson(decoder.decode(midi(0xB0, 20, 0)).single()).value, 0.01f)
        assertEquals(30f, GaugeEvent.fromJson(decoder.decode(midi(0xB0, 20, 64)).single()).value, 0.2f)
        assertEquals(40f, GaugeEvent.fromJson(decoder.decode(midi(0xB0, 20, 127)).single()).value, 0.01f)
    }

    @Test
    fun `an led takes one of its three states from the value`() {
        val decoder = decoder(
            MidiMapping(target = "l", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 5),
            item("l", ItemType.LED)
        )

        assertEquals(LEDSTATE.OFF, LedEvent.fromJson(decoder.decode(midi(0xB0, 5, 0)).single()).state)
        assertEquals(LEDSTATE.ON, LedEvent.fromJson(decoder.decode(midi(0xB0, 5, 64)).single()).state)
        assertEquals(LEDSTATE.BLINK, LedEvent.fromJson(decoder.decode(midi(0xB0, 5, 127)).single()).state)
    }

    @Test
    fun `a note off turns an led off and a full velocity note blinks it`() {
        val decoder = decoder(
            MidiMapping(target = "l", messageType = MidiMessageType.NOTE, channel = 1, number = 60),
            item("l", ItemType.LED)
        )

        assertEquals(LEDSTATE.OFF, LedEvent.fromJson(decoder.decode(midi(0x80, 60, 0)).single()).state)
        assertEquals(LEDSTATE.BLINK, LedEvent.fromJson(decoder.decode(midi(0x90, 60, 127)).single()).state)
        assertEquals(LEDSTATE.ON, LedEvent.fromJson(decoder.decode(midi(0x90, 60, 64)).single()).state)
    }

    @Test
    fun `a switch is gated at the half way point of a control change`() {
        val decoder = decoder(
            MidiMapping(target = "s", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 7),
            item("s", ItemType.SWITCH)
        )

        assertTrue(SwitchEvent.fromJson(decoder.decode(midi(0xB0, 7, 127)).single()).state)
        assertTrue(SwitchEvent.fromJson(decoder.decode(midi(0xB0, 7, 64)).single()).state)
        assertTrue(!SwitchEvent.fromJson(decoder.decode(midi(0xB0, 7, 63)).single()).state)
        assertTrue(!SwitchEvent.fromJson(decoder.decode(midi(0xB0, 7, 0)).single()).state)
    }

    @Test
    fun `a note on and note off open and close a switch`() {
        val decoder = decoder(
            MidiMapping(target = "s", messageType = MidiMessageType.NOTE, channel = 1, number = 36),
            item("s", ItemType.SWITCH)
        )

        assertTrue(SwitchEvent.fromJson(decoder.decode(midi(0x90, 36, 127)).single()).state)
        assertTrue(!SwitchEvent.fromJson(decoder.decode(midi(0x80, 36, 0)).single()).state)
    }

    @Test
    fun `a note on with zero velocity closes a switch like a note off does`() {
        val decoder = decoder(
            MidiMapping(target = "s", messageType = MidiMessageType.NOTE, channel = 1, number = 36),
            item("s", ItemType.SWITCH)
        )

        assertTrue(!SwitchEvent.fromJson(decoder.decode(midi(0x90, 36, 0)).single()).state)
    }

    @Test
    fun `a slider is scaled into its configured range`() {
        val properties = SliderProperties(minValue = -10f, maxValue = 10f).toJson()
        val decoder = decoder(
            MidiMapping(target = "sl", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 1),
            item("sl", ItemType.SLIDER, properties)
        )

        assertEquals(-10f, SliderEvent.fromJson(decoder.decode(midi(0xB0, 1, 0)).single()).value, 0.01f)
        assertEquals(10f, SliderEvent.fromJson(decoder.decode(midi(0xB0, 1, 127)).single()).value, 0.01f)
    }

    @Test
    fun `pitch bend drives a gauge across its full 14 bit span`() {
        val properties = GaugeProperties(minValue = 0f, maxValue = 100f).toJson()
        val decoder = decoder(
            MidiMapping(target = "g", messageType = MidiMessageType.PITCH_BEND, channel = 1),
            item("g", ItemType.GAUGE, properties)
        )

        assertEquals(0f, GaugeEvent.fromJson(decoder.decode(midi(0xE0, 0, 0)).single()).value, 0.01f)
        assertEquals(100f, GaugeEvent.fromJson(decoder.decode(midi(0xE0, 127, 127)).single()).value, 0.01f)
    }

    @Test
    fun `a message on another channel is ignored`() {
        val decoder = decoder(
            MidiMapping(target = "g", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20),
            item("g", ItemType.GAUGE)
        )

        assertTrue(decoder.decode(midi(0xB1, 20, 127)).isEmpty())
    }

    @Test
    fun `a message with another number is ignored`() {
        val decoder = decoder(
            MidiMapping(target = "g", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20),
            item("g", ItemType.GAUGE)
        )

        assertTrue(decoder.decode(midi(0xB0, 21, 127)).isEmpty())
    }

    @Test
    fun `a target whose message type does not match is ignored`() {
        val decoder = decoder(
            MidiMapping(target = "g", messageType = MidiMessageType.NOTE, channel = 1, number = 20),
            item("g", ItemType.GAUGE)
        )

        assertTrue(decoder.decode(midi(0xB0, 20, 127)).isEmpty())
    }

    @Test
    fun `controls that cannot be driven from outside are ignored`() {
        // the incoming path only updates what DroidPad can already update over
        // json, so a button or a dpad direction has nothing to apply
        listOf(ItemType.BUTTON, ItemType.DPAD, ItemType.JOYSTICK, ItemType.LABEL).forEach { itemType ->
            val decoder = decoder(
                MidiMapping(target = "x", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20),
                item("x", itemType)
            )

            assertTrue(itemType.name, decoder.decode(midi(0xB0, 20, 127)).isEmpty())
        }
    }

    @Test
    fun `one message can drive every target mapped to it`() {
        val decoder = MidiDecoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "g", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20),
                    MidiMapping(target = "l", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20)
                )
            ),
            items = listOf(item("g", ItemType.GAUGE), item("l", ItemType.LED))
        )

        assertEquals(2, decoder.decode(midi(0xB0, 20, 127)).size)
    }

    @Test
    fun `a malformed message is dropped rather than throwing`() {
        val decoder = decoder(
            MidiMapping(target = "g", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20),
            item("g", ItemType.GAUGE)
        )

        assertTrue(decoder.decode(ByteArray(0)).isEmpty())
        assertTrue(decoder.decode(midi(0xB0)).isEmpty())
        assertTrue(decoder.decode(midi(0xB0, 20)).isEmpty())
    }
}
