package com.github.umer0586.droidpad.miditests

import com.github.umer0586.droidpad.data.ButtonEvent
import com.github.umer0586.droidpad.data.ControlPadEvent
import com.github.umer0586.droidpad.data.DPadEvent
import com.github.umer0586.droidpad.data.JoyStickEvent
import com.github.umer0586.droidpad.data.SliderEvent
import com.github.umer0586.droidpad.data.SliderProperties
import com.github.umer0586.droidpad.data.SteeringWheelEvent
import com.github.umer0586.droidpad.data.SteeringWheelProperties
import com.github.umer0586.droidpad.data.SwitchEvent
import com.github.umer0586.droidpad.data.connectionconfig.MidiConfig
import com.github.umer0586.droidpad.data.connectionconfig.MidiMapping
import com.github.umer0586.droidpad.data.connectionconfig.MidiMessageType
import com.github.umer0586.droidpad.data.database.entities.ControlPadItem
import com.github.umer0586.droidpad.data.database.entities.ItemType
import com.github.umer0586.droidpad.data.util.midi.MidiEncoder
import com.github.umer0586.droidpad.ui.components.DPAD_BUTTON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

// consumed by e2e/test_midi_wire_format.py, which decodes the emitted bytes
// with an independent MIDI parser
private const val VECTORS_PATH = "build/e2e/midi-vectors.json"

@Serializable
private data class VectorItem(
    val identifier: String,
    val itemType: String,
    val minValue: Float? = null,
    val maxValue: Float? = null
)

@Serializable
private data class VectorMapping(
    val target: String,
    val messageType: String,
    val channel: Int,
    val number: Int
)

@Serializable
private data class VectorEvent(
    val kind: String,
    val value: Float? = null,
    val x: Float? = null,
    val y: Float? = null,
    val state: String? = null,
    val button: String? = null
)

@Serializable
private data class Vector(
    val name: String,
    val item: VectorItem,
    val mappings: List<VectorMapping>,
    val event: VectorEvent,
    val messages: List<String>
)

@Serializable
private data class VectorFile(val vectors: List<Vector>)

private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

@RunWith(JUnit4::class)
class MidiVectorExporterTest {

    @Test
    fun `export midi vectors for the cross language wire format check`() {

        val vectors = buildList {
            val slider = VectorItem("sld", ItemType.SLIDER.name, minValue = 0f, maxValue = 10f)
            val sliderCC = VectorMapping("sld", MidiMessageType.CONTROL_CHANGE.name, channel = 3, number = 74)
            val sliderBend = VectorMapping("sld", MidiMessageType.PITCH_BEND.name, channel = 1, number = 0)

            // a sweep proves the scaling is monotonic and hits both endpoints
            (0..20).forEach { step ->
                val value = step * 0.5f
                add(vector("slider cc sweep $step", slider, sliderCC, VectorEvent("SLIDER", value = value)))
                add(vector("slider bend sweep $step", slider, sliderBend, VectorEvent("SLIDER", value = value)))
            }

            val button = VectorItem("btn", ItemType.BUTTON.name)
            listOf("PRESS", "RELEASE", "CLICK").forEach { state ->
                add(
                    vector(
                        "button note $state",
                        button,
                        VectorMapping("btn", MidiMessageType.NOTE.name, channel = 10, number = 36),
                        VectorEvent("BUTTON", state = state)
                    )
                )
                add(
                    vector(
                        "button program change $state",
                        button,
                        VectorMapping("btn", MidiMessageType.PROGRAM_CHANGE.name, channel = 16, number = 5),
                        VectorEvent("BUTTON", state = state)
                    )
                )
            }

            val switch = VectorItem("sw", ItemType.SWITCH.name)
            listOf(true, false).forEach { state ->
                add(
                    vector(
                        "switch cc $state",
                        switch,
                        VectorMapping("sw", MidiMessageType.CONTROL_CHANGE.name, channel = 1, number = 7),
                        VectorEvent("SWITCH", state = state.toString().uppercase())
                    )
                )
            }

            val wheel = VectorItem("wheel", ItemType.STEERING_WHEEL.name, minValue = -270f, maxValue = 270f)
            listOf(-270f, -135f, 0f, 135f, 270f).forEach { angle ->
                add(
                    vector(
                        "wheel cc $angle",
                        wheel,
                        VectorMapping("wheel", MidiMessageType.CONTROL_CHANGE.name, channel = 2, number = 1),
                        VectorEvent("STEERING_WHEEL", value = angle)
                    )
                )
            }

            val joystick = VectorItem("joy", ItemType.JOYSTICK.name, minValue = -1f, maxValue = 1f)
            listOf(-1f to -1f, 0f to 0f, 1f to 1f).forEach { (x, y) ->
                add(
                    vector(
                        "joystick x $x",
                        joystick,
                        VectorMapping("joy.X", MidiMessageType.CONTROL_CHANGE.name, channel = 4, number = 16),
                        VectorEvent("JOYSTICK", x = x, y = y)
                    )
                )
            }

            val dpad = VectorItem("pad", ItemType.DPAD.name)
            DPAD_BUTTON.entries.forEach { direction ->
                add(
                    vector(
                        "dpad note $direction",
                        dpad,
                        VectorMapping("pad.$direction", MidiMessageType.NOTE.name, channel = 1, number = 40),
                        VectorEvent("DPAD", state = "PRESS", button = direction.name)
                    )
                )
            }
        }

        val file = File(VECTORS_PATH)
        file.parentFile?.mkdirs()
        file.writeText(Json { prettyPrint = true }.encodeToString(VectorFile(vectors)))

        assertTrue("vectors should not be empty", vectors.isNotEmpty())
        assertEquals(
            "a program change release is the only interaction with nothing to send",
            listOf("button program change RELEASE"),
            vectors.filter { it.messages.isEmpty() }.map { it.name }
        )
    }

    private fun vector(name: String, item: VectorItem, mapping: VectorMapping, event: VectorEvent): Vector {
        val encoder = MidiEncoder(
            config = MidiConfig(
                mappings = listOf(
                    MidiMapping(
                        target = mapping.target,
                        messageType = MidiMessageType.valueOf(mapping.messageType),
                        channel = mapping.channel,
                        number = mapping.number
                    )
                )
            ),
            items = listOf(controlPadItem(item))
        )

        return Vector(
            name = name,
            item = item,
            mappings = listOf(mapping),
            event = event,
            messages = encoder.encode(controlPadEvent(item.identifier, event)).map { it.toHex() }
        )
    }

    private fun controlPadItem(item: VectorItem): ControlPadItem {
        val itemType = ItemType.valueOf(item.itemType)
        val properties = when (itemType) {
            ItemType.SLIDER -> SliderProperties(minValue = item.minValue!!, maxValue = item.maxValue!!).toJson()
            ItemType.STEERING_WHEEL -> SteeringWheelProperties(maxAngle = item.maxValue!!.toInt()).toJson()
            else -> "{}"
        }
        return ControlPadItem(
            itemIdentifier = item.identifier,
            controlPadId = 1,
            itemType = itemType,
            properties = properties
        )
    }

    private fun controlPadEvent(identifier: String, event: VectorEvent): ControlPadEvent = when (event.kind) {
        "SLIDER" -> SliderEvent(id = identifier, value = event.value!!)
        "SWITCH" -> SwitchEvent(id = identifier, state = event.state.toBoolean())
        "BUTTON" -> ButtonEvent(id = identifier, state = event.state!!)
        "DPAD" -> DPadEvent(id = identifier, button = DPAD_BUTTON.valueOf(event.button!!), state = event.state!!)
        "JOYSTICK" -> JoyStickEvent(id = identifier, x = event.x!!, y = event.y!!)
        "STEERING_WHEEL" -> SteeringWheelEvent(id = identifier, angle = event.value!!)
        else -> throw IllegalArgumentException("unknown event kind ${event.kind}")
    }
}
