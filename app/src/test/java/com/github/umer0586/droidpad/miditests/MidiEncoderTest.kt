package com.github.umer0586.droidpad.miditests

import com.github.umer0586.droidpad.data.ButtonEvent
import com.github.umer0586.droidpad.data.DPadEvent
import com.github.umer0586.droidpad.data.JoyStickEvent
import com.github.umer0586.droidpad.data.SliderEvent
import com.github.umer0586.droidpad.data.SliderProperties
import com.github.umer0586.droidpad.data.SteeringWheelEvent
import com.github.umer0586.droidpad.data.SteeringWheelProperties
import com.github.umer0586.droidpad.data.StepSliderProperties
import com.github.umer0586.droidpad.data.SwitchEvent
import com.github.umer0586.droidpad.data.connectionconfig.MidiConfig
import com.github.umer0586.droidpad.data.connectionconfig.MidiMapping
import com.github.umer0586.droidpad.data.connectionconfig.MidiMessageType
import com.github.umer0586.droidpad.data.database.entities.ControlPadItem
import com.github.umer0586.droidpad.data.database.entities.ItemType
import com.github.umer0586.droidpad.data.util.midi.MidiEncoder
import com.github.umer0586.droidpad.data.util.midi.midiMappingsFor
import com.github.umer0586.droidpad.data.util.midi.midiTargetsOf
import com.github.umer0586.droidpad.ui.components.DPAD_BUTTON
import org.junit.Assert.assertArrayEquals
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

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

@RunWith(JUnit4::class)
class MidiEncoderTest {

    @Test
    fun `button press and release become note on and note off`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "btn", messageType = MidiMessageType.NOTE, channel = 1, number = 60)
                )
            ),
            items = listOf(item("btn", ItemType.BUTTON))
        )

        assertArrayEquals(
            bytes(0x90, 60, 127),
            encoder.encode(ButtonEvent(id = "btn", state = "PRESS")).single()
        )
        assertArrayEquals(
            bytes(0x80, 60, 0),
            encoder.encode(ButtonEvent(id = "btn", state = "RELEASE")).single()
        )
    }

    @Test
    fun `button click becomes a note on followed by a note off`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "btn", messageType = MidiMessageType.NOTE, channel = 1, number = 60)
                )
            ),
            items = listOf(item("btn", ItemType.BUTTON))
        )

        val messages = encoder.encode(ButtonEvent(id = "btn", state = "CLICK"))

        assertEquals(2, messages.size)
        assertArrayEquals(bytes(0x90, 60, 127), messages[0])
        assertArrayEquals(bytes(0x80, 60, 0), messages[1])
    }

    @Test
    fun `switch toggles a control change between its extremes`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "sw", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 7)
                )
            ),
            items = listOf(item("sw", ItemType.SWITCH))
        )

        assertArrayEquals(
            bytes(0xB0, 7, 127),
            encoder.encode(SwitchEvent(id = "sw", state = true)).single()
        )
        assertArrayEquals(
            bytes(0xB0, 7, 0),
            encoder.encode(SwitchEvent(id = "sw", state = false)).single()
        )
    }

    @Test
    fun `channel is encoded in the low nibble of the status byte`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "sw", messageType = MidiMessageType.CONTROL_CHANGE, channel = 16, number = 7)
                )
            ),
            items = listOf(item("sw", ItemType.SWITCH))
        )

        assertArrayEquals(
            bytes(0xBF, 7, 127),
            encoder.encode(SwitchEvent(id = "sw", state = true)).single()
        )
    }

    @Test
    fun `slider value is scaled from its configured range onto 0 to 127`() {
        val properties = SliderProperties(minValue = 0f, maxValue = 10f).toJson()
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "sld", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 1)
                )
            ),
            items = listOf(item("sld", ItemType.SLIDER, properties))
        )

        assertArrayEquals(bytes(0xB0, 1, 0), encoder.encode(SliderEvent(id = "sld", value = 0f)).single())
        assertArrayEquals(bytes(0xB0, 1, 64), encoder.encode(SliderEvent(id = "sld", value = 5f)).single())
        assertArrayEquals(bytes(0xB0, 1, 127), encoder.encode(SliderEvent(id = "sld", value = 10f)).single())
    }

    @Test
    fun `slider value outside its configured range is clamped`() {
        val properties = SliderProperties(minValue = -5f, maxValue = 5f).toJson()
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "sld", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 1)
                )
            ),
            items = listOf(item("sld", ItemType.SLIDER, properties))
        )

        assertArrayEquals(bytes(0xB0, 1, 0), encoder.encode(SliderEvent(id = "sld", value = -50f)).single())
        assertArrayEquals(bytes(0xB0, 1, 127), encoder.encode(SliderEvent(id = "sld", value = 50f)).single())
    }

    @Test
    fun `step slider is scaled from its own properties`() {
        val properties = StepSliderProperties(minValue = 0f, maxValue = 4f, steps = 3).toJson()
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "step", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 2)
                )
            ),
            items = listOf(item("step", ItemType.STEP_SLIDER, properties))
        )

        assertArrayEquals(bytes(0xB0, 2, 0), encoder.encode(SliderEvent(id = "step", value = 0f)).single())
        assertArrayEquals(bytes(0xB0, 2, 127), encoder.encode(SliderEvent(id = "step", value = 4f)).single())
    }

    @Test
    fun `joystick axes are mapped independently`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "joy.X", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 10),
                    MidiMapping(target = "joy.Y", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 11)
                )
            ),
            items = listOf(item("joy", ItemType.JOYSTICK))
        )

        val messages = encoder.encode(JoyStickEvent(id = "joy", x = 1f, y = -1f))

        assertEquals(2, messages.size)
        assertArrayEquals(bytes(0xB0, 10, 127), messages[0])
        assertArrayEquals(bytes(0xB0, 11, 0), messages[1])
    }

    @Test
    fun `only the mapped joystick axis is emitted`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "joy.Y", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 11)
                )
            ),
            items = listOf(item("joy", ItemType.JOYSTICK))
        )

        assertArrayEquals(
            bytes(0xB0, 11, 127),
            encoder.encode(JoyStickEvent(id = "joy", x = 1f, y = 1f)).single()
        )
    }

    @Test
    fun `each dpad direction has its own mapping`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "pad.UP", messageType = MidiMessageType.NOTE, channel = 1, number = 36),
                    MidiMapping(target = "pad.DOWN", messageType = MidiMessageType.NOTE, channel = 1, number = 38)
                )
            ),
            items = listOf(item("pad", ItemType.DPAD))
        )

        assertArrayEquals(
            bytes(0x90, 36, 127),
            encoder.encode(DPadEvent(id = "pad", button = DPAD_BUTTON.UP, state = "PRESS")).single()
        )
        assertArrayEquals(
            bytes(0x80, 38, 0),
            encoder.encode(DPadEvent(id = "pad", button = DPAD_BUTTON.DOWN, state = "RELEASE")).single()
        )
        assertTrue(
            encoder.encode(DPadEvent(id = "pad", button = DPAD_BUTTON.LEFT, state = "PRESS")).isEmpty()
        )
    }

    @Test
    fun `steering wheel angle is scaled across its full travel`() {
        val properties = SteeringWheelProperties(maxAngle = 360).toJson()
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "wheel", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20)
                )
            ),
            items = listOf(item("wheel", ItemType.STEERING_WHEEL, properties))
        )

        assertArrayEquals(bytes(0xB0, 20, 0), encoder.encode(SteeringWheelEvent(id = "wheel", angle = -360f)).single())
        assertArrayEquals(bytes(0xB0, 20, 64), encoder.encode(SteeringWheelEvent(id = "wheel", angle = 0f)).single())
        assertArrayEquals(bytes(0xB0, 20, 127), encoder.encode(SteeringWheelEvent(id = "wheel", angle = 360f)).single())
    }

    @Test
    fun `pitch bend is split into a 14 bit little endian pair`() {
        val properties = SliderProperties(minValue = 0f, maxValue = 1f).toJson()
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "sld", messageType = MidiMessageType.PITCH_BEND, channel = 1, number = 0)
                )
            ),
            items = listOf(item("sld", ItemType.SLIDER, properties))
        )

        assertArrayEquals(bytes(0xE0, 0, 0), encoder.encode(SliderEvent(id = "sld", value = 0f)).single())
        assertArrayEquals(bytes(0xE0, 127, 127), encoder.encode(SliderEvent(id = "sld", value = 1f)).single())
    }

    @Test
    fun `a discrete pitch bend mapping returns to center when released`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "btn", messageType = MidiMessageType.PITCH_BEND, channel = 1, number = 0)
                )
            ),
            items = listOf(item("btn", ItemType.BUTTON))
        )

        assertArrayEquals(
            bytes(0xE0, 127, 127),
            encoder.encode(ButtonEvent(id = "btn", state = "PRESS")).single()
        )
        // 8192 is the 14 bit center: lsb 0, msb 64
        assertArrayEquals(
            bytes(0xE0, 0, 64),
            encoder.encode(ButtonEvent(id = "btn", state = "RELEASE")).single()
        )
    }

    @Test
    fun `program change is a two byte message and has no release`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "btn", messageType = MidiMessageType.PROGRAM_CHANGE, channel = 2, number = 5)
                )
            ),
            items = listOf(item("btn", ItemType.BUTTON))
        )

        assertArrayEquals(
            bytes(0xC1, 5),
            encoder.encode(ButtonEvent(id = "btn", state = "PRESS")).single()
        )
        assertTrue(encoder.encode(ButtonEvent(id = "btn", state = "RELEASE")).isEmpty())
    }

    @Test
    fun `an unmapped item emits nothing`() {
        val encoder = MidiEncoder(
            config = MidiConfig(mappings = emptyList()),
            items = listOf(item("btn", ItemType.BUTTON))
        )

        assertTrue(encoder.encode(ButtonEvent(id = "btn", state = "PRESS")).isEmpty())
    }

    @Test
    fun `an out of range channel or number is clamped onto the wire`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "sw", messageType = MidiMessageType.CONTROL_CHANGE, channel = 99, number = 999)
                )
            ),
            items = listOf(item("sw", ItemType.SWITCH))
        )

        assertArrayEquals(
            bytes(0xBF, 127, 127),
            encoder.encode(SwitchEvent(id = "sw", state = true)).single()
        )
    }

    @Test
    fun `a slider with a collapsed range does not divide by zero`() {
        val properties = SliderProperties(minValue = 3f, maxValue = 3f).toJson()
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "sld", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 1)
                )
            ),
            items = listOf(item("sld", ItemType.SLIDER, properties))
        )

        assertArrayEquals(bytes(0xB0, 1, 0), encoder.encode(SliderEvent(id = "sld", value = 3f)).single())
    }

    @Test
    fun `single target items expose one mapping target keyed by their identifier`() {
        listOf(ItemType.BUTTON, ItemType.SWITCH, ItemType.SLIDER, ItemType.STEP_SLIDER, ItemType.STEERING_WHEEL)
            .forEach { itemType ->
                val targets = midiTargetsOf(item("x", itemType))
                assertEquals(itemType.name, listOf("x"), targets.map { it.key })
            }
    }

    @Test
    fun `dpad and joystick expand into one target per direction and axis`() {
        assertEquals(
            listOf("pad.UP", "pad.DOWN", "pad.LEFT", "pad.RIGHT"),
            midiTargetsOf(item("pad", ItemType.DPAD)).map { it.key }
        )
        assertEquals(
            listOf("joy.X", "joy.Y"),
            midiTargetsOf(item("joy", ItemType.JOYSTICK)).map { it.key }
        )
    }

    @Test
    fun `a label has no mapping target because it neither sends nor receives`() {
        assertTrue(midiTargetsOf(item("x", ItemType.LABEL)).isEmpty())
    }

    @Test
    fun `led and gauge are mapping targets so incoming midi can drive them`() {
        listOf(ItemType.LED, ItemType.GAUGE).forEach { itemType ->
            assertEquals(itemType.name, listOf("x"), midiTargetsOf(item("x", itemType)).map { it.key })
        }
    }

    @Test
    fun `a receive only item never emits, even when it is mapped`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(target = "g", messageType = MidiMessageType.CONTROL_CHANGE, channel = 1, number = 20)
                )
            ),
            items = listOf(item("g", ItemType.GAUGE))
        )

        // a gauge raises no control pad event, so nothing should reach the wire
        assertTrue(encoder.encode(SliderEvent(id = "nothing", value = 1f)).isEmpty())
    }

    @Test
    fun `a target mapped to NONE stays silent`() {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(MidiMapping(target = "btn", messageType = MidiMessageType.NONE))
            ),
            items = listOf(item("btn", ItemType.BUTTON))
        )

        assertTrue(encoder.encode(ButtonEvent(id = "btn", state = "PRESS")).isEmpty())
        assertTrue(encoder.encode(ButtonEvent(id = "btn", state = "CLICK")).isEmpty())
    }

    @Test
    fun `fresh mappings number continuous and on off targets separately`() {
        val targets = midiTargetsOf(item("sld", ItemType.SLIDER)) +
                midiTargetsOf(item("btn", ItemType.BUTTON)) +
                midiTargetsOf(item("joy", ItemType.JOYSTICK)) +
                midiTargetsOf(item("sw", ItemType.SWITCH))

        val mappings = midiMappingsFor(targets)

        assertEquals(targets.map { it.key }, mappings.map { it.target })
        assertEquals(
            listOf(
                MidiMessageType.CONTROL_CHANGE,
                MidiMessageType.NOTE,
                MidiMessageType.CONTROL_CHANGE,
                MidiMessageType.CONTROL_CHANGE,
                MidiMessageType.NOTE
            ),
            mappings.map { it.messageType }
        )
        assertEquals(listOf(1, 36, 2, 3, 37), mappings.map { it.number })
    }

    @Test
    fun `a stored mapping survives and its choices are kept`() {
        val targets = midiTargetsOf(item("btn", ItemType.BUTTON))
        val stored = listOf(
            MidiMapping(target = "btn", messageType = MidiMessageType.PROGRAM_CHANGE, channel = 9, number = 99)
        )

        assertEquals(stored, midiMappingsFor(targets, stored))
    }

    @Test
    fun `a target added since the config was saved never reuses a taken number`() {
        val targets = midiTargetsOf(item("a", ItemType.BUTTON)) + midiTargetsOf(item("b", ItemType.BUTTON))
        // "a" was renumbered onto the note positional defaulting would hand to "b"
        val stored = listOf(MidiMapping(target = "a", messageType = MidiMessageType.NOTE, number = 37))

        val mappings = midiMappingsFor(targets, stored)

        assertEquals(listOf(37, 36), mappings.map { it.number })
        assertEquals(2, mappings.map { it.number }.toSet().size)
    }

    @Test
    fun `notes and controllers are numbered from separate pools`() {
        val targets = midiTargetsOf(item("sld", ItemType.SLIDER)) + midiTargetsOf(item("btn", ItemType.BUTTON))
        // a controller on 36 must not push the new note off 36
        val stored = listOf(MidiMapping(target = "sld", messageType = MidiMessageType.CONTROL_CHANGE, number = 36))

        assertEquals(listOf(36, 36), midiMappingsFor(targets, stored).map { it.number })
    }

    @Test
    fun `a mapping for a control that no longer exists is dropped`() {
        val targets = midiTargetsOf(item("btn", ItemType.BUTTON))
        val stored = listOf(
            MidiMapping(target = "btn", messageType = MidiMessageType.NOTE, number = 50),
            MidiMapping(target = "deleted", messageType = MidiMessageType.NOTE, number = 51)
        )

        assertEquals(listOf("btn"), midiMappingsFor(targets, stored).map { it.target })
    }
}
