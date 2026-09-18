/*
 *     This file is a part of DroidPad (https://www.github.com/UmerCodez/DroidPad)
 *     Copyright (C) 2025 Umer Farooq (umerfarooq2383@gmail.com)
 *
 *     DroidPad is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     DroidPad is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with DroidPad. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.github.umer0586.droidpad.data.util.midi

import com.github.umer0586.droidpad.data.GaugeEvent
import com.github.umer0586.droidpad.data.LedEvent
import com.github.umer0586.droidpad.data.SliderEvent
import com.github.umer0586.droidpad.data.SwitchEvent
import com.github.umer0586.droidpad.data.connectionconfig.MIDI_MAX_DATA
import com.github.umer0586.droidpad.data.connectionconfig.MidiConfig
import com.github.umer0586.droidpad.data.connectionconfig.MidiMapping
import com.github.umer0586.droidpad.data.connectionconfig.MidiMessageType
import com.github.umer0586.droidpad.data.database.entities.ControlPadItem
import com.github.umer0586.droidpad.data.database.entities.ItemType
import com.github.umer0586.droidpad.ui.components.LEDSTATE

private const val STATUS_BIT = 0x80
private const val STATUS_KIND_MASK = 0xF0
private const val STATUS_CHANNEL_MASK = 0x0F

private const val SYSEX_START = 0xF0
private const val SYSEX_END = 0xF7
private const val REALTIME_START = 0xF8

private const val CHANNEL_PRESSURE_STATUS = 0xD0

// a control change at or past half travel reads as "on" for a two state target
private const val GATE_THRESHOLD = 64

// an LED shows one of three states, chosen by where the value falls; declaring
// them here rather than using LEDSTATE's own order keeps the wire meaning
// stable if that enum is ever reordered
private val LED_STATES = listOf(LEDSTATE.OFF, LEDSTATE.ON, LEDSTATE.BLINK)

/**
 * Frames a MIDI byte stream into whole channel voice messages.
 *
 * Stateful by necessity: a port hands over arbitrary chunks, a message can
 * straddle two of them, and running status lets a sender omit the status byte
 * of every message after the first.
 */
class MidiStreamParser {

    private var status = 0
    private var inSysex = false
    private val data = mutableListOf<Int>()

    fun accept(data: ByteArray, offset: Int = 0, count: Int = data.size): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()

        for (index in offset until offset + count) {
            accept(data[index].toInt() and 0xFF)?.also { messages.add(it) }
        }

        return messages
    }

    private fun accept(byte: Int): ByteArray? {

        // realtime bytes are legal anywhere, including between the data bytes
        // of another message, and they leave running status alone
        if (byte >= REALTIME_START) return null

        if (inSysex) {
            if (byte == SYSEX_END) inSysex = false
            return null
        }

        if (byte >= STATUS_BIT) {
            data.clear()

            when {
                byte == SYSEX_START -> { inSysex = true; status = 0 }
                // anything else above the channel voice range is system common,
                // which cancels running status
                byte > CHANNEL_VOICE_MAX -> status = 0
                else -> status = byte
            }

            return null
        }

        // a data byte with no status to attach it to is unusable
        if (status == 0) return null

        data.add(byte)
        if (data.size < dataLengthOf(status)) return null

        val message = ByteArray(data.size + 1)
        message[0] = status.toByte()
        data.forEachIndexed { index, value -> message[index + 1] = value.toByte() }

        // running status keeps the status byte for the next message
        data.clear()
        return message
    }
}

/**
 * Turns incoming MIDI into the same JSON messages a script would send, so the
 * control pad applies them through its existing update path. The mirror of
 * [MidiEncoder], sharing its mapping table: whatever number drives a control
 * outward is the number that drives it inward.
 */
class MidiDecoder(config: MidiConfig, items: List<ControlPadItem>) {

    private val mappings = config.mappings
    private val itemsByIdentifier = items.associateBy { it.itemIdentifier }

    fun decode(message: ByteArray): List<String> {

        val reading = readingOf(message) ?: return emptyList()

        return mappings.filter { it.matches(reading) }
            .mapNotNull { mapping -> itemsByIdentifier[mapping.target]?.let { event(it, reading) } }
    }

    private fun event(item: ControlPadItem, reading: Reading): String? = when (item.itemType) {

        ItemType.SWITCH -> SwitchEvent(id = item.itemIdentifier, state = reading.gate).toJson()

        ItemType.SLIDER, ItemType.STEP_SLIDER ->
            SliderEvent(id = item.itemIdentifier, value = item.scale(reading.normalized)).toJson()

        ItemType.GAUGE -> GaugeEvent(id = item.itemIdentifier, value = item.scale(reading.normalized)).toJson()

        ItemType.LED -> LedEvent(id = item.itemIdentifier, state = ledStateOf(reading.normalized)).toJson()

        // the incoming path can only update what a script could already update
        ItemType.BUTTON, ItemType.DPAD, ItemType.JOYSTICK, ItemType.STEERING_WHEEL, ItemType.LABEL -> null
    }

    private fun ControlPadItem.scale(normalized: Float) = midiValueRange().let {
        it.start + normalized * (it.endInclusive - it.start)
    }
}

// what a single message says, independent of the control it ends up driving
private data class Reading(
    val kind: Int,
    val channel: Int,
    val number: Int,
    val gate: Boolean,
    val normalized: Float
)

private fun readingOf(message: ByteArray): Reading? {

    if (message.isEmpty()) return null

    val status = message[0].toInt() and 0xFF
    if (status < STATUS_BIT) return null

    val kind = status and STATUS_KIND_MASK
    val channel = status and STATUS_CHANNEL_MASK
    val data = message.drop(1).map { it.toInt() and 0xFF }
    if (data.size < dataLengthOf(status)) return null

    return when (kind) {

        NOTE_OFF_STATUS -> Reading(kind, channel, data[0], gate = false, normalized = 0f)

        // a note on at zero velocity is the customary way to write a note off
        NOTE_ON_STATUS -> Reading(kind, channel, data[0], data[1] > 0, data[1] / MIDI_MAX_DATA.toFloat())

        CONTROL_CHANGE_STATUS ->
            Reading(kind, channel, data[0], data[1] >= GATE_THRESHOLD, data[1] / MIDI_MAX_DATA.toFloat())

        PROGRAM_CHANGE_STATUS -> Reading(kind, channel, data[0], gate = true, normalized = data[0] / MIDI_MAX_DATA.toFloat())

        PITCH_BEND_STATUS -> {
            val bend = data[0] or (data[1] shl 7)
            Reading(kind, channel, number = 0, gate = bend >= PITCH_BEND_CENTER, normalized = bend / PITCH_BEND_MAX.toFloat())
        }

        else -> null
    }
}

private fun MidiMapping.matches(reading: Reading) = when (messageType) {
    MidiMessageType.NOTE -> reading.kind in setOf(NOTE_ON_STATUS, NOTE_OFF_STATUS) && number == reading.number
    MidiMessageType.CONTROL_CHANGE -> reading.kind == CONTROL_CHANGE_STATUS && number == reading.number
    MidiMessageType.PROGRAM_CHANGE -> reading.kind == PROGRAM_CHANGE_STATUS && number == reading.number
    MidiMessageType.PITCH_BEND -> reading.kind == PITCH_BEND_STATUS
    MidiMessageType.NONE -> false
} && channel - 1 == reading.channel

private fun ledStateOf(normalized: Float) =
    LED_STATES[(normalized * LED_STATES.size).toInt().coerceIn(0, LED_STATES.lastIndex)]

// program change and channel pressure carry one data byte, everything else two
internal fun dataLengthOf(status: Int) =
    if (status and STATUS_KIND_MASK in setOf(PROGRAM_CHANGE_STATUS, CHANNEL_PRESSURE_STATUS)) 1 else 2
