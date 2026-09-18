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

import com.github.umer0586.droidpad.data.ButtonEvent
import com.github.umer0586.droidpad.data.CLICK_STATE
import com.github.umer0586.droidpad.data.ControlPadEvent
import com.github.umer0586.droidpad.data.DPadEvent
import com.github.umer0586.droidpad.data.GaugeProperties
import com.github.umer0586.droidpad.data.JoyStickEvent
import com.github.umer0586.droidpad.data.PRESS_STATE
import com.github.umer0586.droidpad.data.RELEASE_STATE
import com.github.umer0586.droidpad.data.SliderEvent
import com.github.umer0586.droidpad.data.SliderProperties
import com.github.umer0586.droidpad.data.SteeringWheelEvent
import com.github.umer0586.droidpad.data.SteeringWheelProperties
import com.github.umer0586.droidpad.data.StepSliderProperties
import com.github.umer0586.droidpad.data.SwitchEvent
import com.github.umer0586.droidpad.data.connectionconfig.MIDI_MAX_CHANNEL
import com.github.umer0586.droidpad.data.connectionconfig.MIDI_MAX_DATA
import com.github.umer0586.droidpad.data.connectionconfig.MIDI_MIN_CHANNEL
import com.github.umer0586.droidpad.data.connectionconfig.MIDI_MIN_DATA
import com.github.umer0586.droidpad.data.connectionconfig.MidiConfig
import com.github.umer0586.droidpad.data.connectionconfig.MidiMapping
import com.github.umer0586.droidpad.data.connectionconfig.MidiMessageType
import com.github.umer0586.droidpad.data.database.entities.ControlPadItem
import com.github.umer0586.droidpad.data.database.entities.ItemType
import com.github.umer0586.droidpad.ui.components.DPAD_BUTTON

// status bytes of the channel voice messages DroidPad speaks, the low nibble
// of each carries the channel. shared with MidiDecoder, which reads the same
// messages coming the other way
internal const val NOTE_OFF_STATUS = 0x80
internal const val NOTE_ON_STATUS = 0x90
internal const val CONTROL_CHANGE_STATUS = 0xB0
internal const val PROGRAM_CHANGE_STATUS = 0xC0
internal const val PITCH_BEND_STATUS = 0xE0
internal const val CHANNEL_VOICE_MAX = 0xEF

// pitch bend is the one 14 bit message, sent as two seven bit halves
internal const val PITCH_BEND_MAX = 16383
internal const val PITCH_BEND_CENTER = 8192
private const val SEVEN_BIT_MASK = 0x7F
private const val SEVEN_BIT_SHIFT = 7

private const val TARGET_SEPARATOR = "."
private const val JOYSTICK_X_AXIS = "X"
private const val JOYSTICK_Y_AXIS = "Y"

// a joystick always reports both axes over the same normalised span
private val JOYSTICK_RANGE = -1f..1f
private val UNIT_RANGE = 0f..1f

// starting points when a mapping table is first laid out: C2 is where pad
// controllers conventionally begin, and CC 1 is the lowest freely usable
// continuous controller
private const val FIRST_DEFAULT_NOTE = 36
private const val FIRST_DEFAULT_CONTROLLER = 1

data class MidiTarget(
    val key: String,
    val label: String,
    // whether the control sweeps a range rather than reporting on and off
    val continuous: Boolean
)

/**
 * The independently mappable controls of an item. A DPAD carries four and a
 * joystick two, so those expand into one target per direction or axis; every
 * other interactive item is addressed by its identifier alone. Both the mapping
 * editor and [MidiEncoder] derive their keys from here so they cannot drift.
 */
fun midiTargetsOf(item: ControlPadItem): List<MidiTarget> = when (item.itemType) {

    ItemType.DPAD -> DPAD_BUTTON.entries.map { item.targetFor(it.name, continuous = false) }
    ItemType.JOYSTICK -> listOf(JOYSTICK_X_AXIS, JOYSTICK_Y_AXIS).map { item.targetFor(it, continuous = true) }

    ItemType.BUTTON, ItemType.SWITCH ->
        listOf(MidiTarget(item.itemIdentifier, item.itemIdentifier, continuous = false))

    // LED and GAUGE never send, but they are mappable so incoming MIDI can
    // drive them, and a controller carries the whole of their range
    ItemType.SLIDER, ItemType.STEP_SLIDER, ItemType.STEERING_WHEEL, ItemType.LED, ItemType.GAUGE ->
        listOf(MidiTarget(item.itemIdentifier, item.itemIdentifier, continuous = true))

    ItemType.LABEL -> emptyList()
}

/**
 * One mapping per target, keeping whatever was already configured. Targets the
 * stored table has never seen take the lowest number nothing else is using, so
 * adding a control cannot land it on top of a control that was renumbered by
 * hand. Notes and controllers are numbered from separate pools because they
 * cannot collide with each other on the wire.
 */
fun midiMappingsFor(targets: List<MidiTarget>, stored: List<MidiMapping> = emptyList()): List<MidiMapping> {

    val byTarget = stored.associateBy { it.target }
    val takenNotes = mutableSetOf<Int>()
    val takenControllers = mutableSetOf<Int>()

    targets.mapNotNull { byTarget[it.key] }.forEach { mapping ->
        when (mapping.messageType) {
            MidiMessageType.NOTE -> takenNotes.add(mapping.number)
            MidiMessageType.CONTROL_CHANGE -> takenControllers.add(mapping.number)
            else -> {}
        }
    }

    return targets.map { target ->
        byTarget[target.key] ?: if (target.continuous)
            MidiMapping(target.key, MidiMessageType.CONTROL_CHANGE, number = takenControllers.claimFrom(FIRST_DEFAULT_CONTROLLER))
        else
            MidiMapping(target.key, MidiMessageType.NOTE, number = takenNotes.claimFrom(FIRST_DEFAULT_NOTE))
    }
}

private fun MutableSet<Int>.claimFrom(first: Int): Int {
    var number = first
    while (number in this && number < MIDI_MAX_DATA) number++
    add(number)
    return number
}

private fun ControlPadItem.targetFor(suffix: String, continuous: Boolean) =
    MidiTarget("$itemIdentifier$TARGET_SEPARATOR$suffix", "$itemIdentifier $suffix", continuous)

/**
 * Translates control pad interactions into MIDI 1.0 channel voice messages.
 * Continuous controls are scaled from the range configured on the item, so the
 * encoder needs the items alongside the mapping table.
 */
class MidiEncoder(config: MidiConfig, items: List<ControlPadItem>) {

    private val mappings = config.mappings.associateBy { it.target }
    private val ranges = items.associate { it.itemIdentifier to it.midiValueRange() }

    fun encode(event: ControlPadEvent): List<ByteArray> = when (event) {

        is SwitchEvent -> switched(event.id, event.state)
        is ButtonEvent -> momentary(event.id, event.state)
        is DPadEvent -> momentary("${event.id}$TARGET_SEPARATOR${event.button}", event.state)

        // a single target item is addressed by its identifier, so the target and
        // the item the range comes from are the same key
        is SliderEvent -> continuous(event.id, event.value, rangeOf(event.id))
        is SteeringWheelEvent -> continuous(event.id, event.angle, rangeOf(event.id))

        is JoyStickEvent -> {
            val range = rangeOf(event.id, fallback = JOYSTICK_RANGE)
            continuous("${event.id}$TARGET_SEPARATOR$JOYSTICK_X_AXIS", event.x, range) +
                    continuous("${event.id}$TARGET_SEPARATOR$JOYSTICK_Y_AXIS", event.y, range)
        }
    }

    // an item the encoder was not given cannot be scaled, so it falls back to
    // the range its events are already normalised to
    private fun rangeOf(itemIdentifier: String, fallback: ClosedFloatingPointRange<Float> = UNIT_RANGE) =
        ranges[itemIdentifier] ?: fallback

    private fun momentary(target: String, state: String): List<ByteArray> = when (state) {
        PRESS_STATE -> switched(target, true)
        RELEASE_STATE -> switched(target, false)
        // a tap has no dwell time of its own, so it is sent as an immediate pair
        CLICK_STATE -> switched(target, true) + switched(target, false)
        else -> emptyList()
    }

    private fun switched(target: String, on: Boolean): List<ByteArray> {
        val mapping = mappings[target] ?: return emptyList()
        val channel = mapping.wireChannel
        val number = mapping.wireNumber

        return when (mapping.messageType) {
            MidiMessageType.NONE -> emptyList()

            MidiMessageType.NOTE ->
                if (on) listOf(midiBytes(NOTE_ON_STATUS or channel, number, MIDI_MAX_DATA))
                else listOf(midiBytes(NOTE_OFF_STATUS or channel, number, MIDI_MIN_DATA))

            MidiMessageType.CONTROL_CHANGE ->
                listOf(midiBytes(CONTROL_CHANGE_STATUS or channel, number, if (on) MIDI_MAX_DATA else MIDI_MIN_DATA))

            // a program change selects a patch, there is nothing to undo on release
            MidiMessageType.PROGRAM_CHANGE ->
                if (on) listOf(midiBytes(PROGRAM_CHANGE_STATUS or channel, number)) else emptyList()

            MidiMessageType.PITCH_BEND ->
                listOf(pitchBend(channel, if (on) PITCH_BEND_MAX else PITCH_BEND_CENTER))
        }
    }

    private fun continuous(target: String, value: Float, range: ClosedFloatingPointRange<Float>): List<ByteArray> {
        val mapping = mappings[target] ?: return emptyList()
        val channel = mapping.wireChannel
        val normalized = normalize(value, range)
        val data = Math.round(normalized * MIDI_MAX_DATA)

        return when (mapping.messageType) {
            MidiMessageType.NONE -> emptyList()
            MidiMessageType.NOTE -> listOf(midiBytes(NOTE_ON_STATUS or channel, mapping.wireNumber, data))
            MidiMessageType.CONTROL_CHANGE -> listOf(midiBytes(CONTROL_CHANGE_STATUS or channel, mapping.wireNumber, data))
            MidiMessageType.PROGRAM_CHANGE -> listOf(midiBytes(PROGRAM_CHANGE_STATUS or channel, data))
            MidiMessageType.PITCH_BEND -> listOf(pitchBend(channel, Math.round(normalized * PITCH_BEND_MAX)))
        }
    }
}

private val MidiMapping.wireChannel get() = channel.coerceIn(MIDI_MIN_CHANNEL, MIDI_MAX_CHANNEL) - 1
private val MidiMapping.wireNumber get() = number.coerceIn(MIDI_MIN_DATA, MIDI_MAX_DATA)

private fun normalize(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span == 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

private fun pitchBend(channel: Int, value: Int) = midiBytes(
    PITCH_BEND_STATUS or channel,
    value and SEVEN_BIT_MASK,
    (value shr SEVEN_BIT_SHIFT) and SEVEN_BIT_MASK
)

private fun midiBytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

// the span an item's values live in, scaled onto MIDI on the way out and back
// off it on the way in
internal fun ControlPadItem.midiValueRange(): ClosedFloatingPointRange<Float> = when (itemType) {

    ItemType.SLIDER -> SliderProperties.fromJson(properties).let { it.minValue..it.maxValue }
    ItemType.STEP_SLIDER -> StepSliderProperties.fromJson(properties).let { it.minValue..it.maxValue }
    ItemType.GAUGE -> GaugeProperties.fromJson(properties).let { it.minValue..it.maxValue }

    ItemType.STEERING_WHEEL -> SteeringWheelProperties.fromJson(properties).maxAngle.toFloat().let { -it..it }

    ItemType.JOYSTICK -> JOYSTICK_RANGE

    else -> UNIT_RANGE
}
