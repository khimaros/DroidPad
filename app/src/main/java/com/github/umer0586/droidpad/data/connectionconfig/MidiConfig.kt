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

package com.github.umer0586.droidpad.data.connectionconfig

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// MIDI 1.0 numbers channels 1..16 for humans and 0..15 on the wire,
// and every data byte carries seven usable bits
const val MIDI_MIN_CHANNEL = 1
const val MIDI_MAX_CHANNEL = 16
const val MIDI_MIN_DATA = 0
const val MIDI_MAX_DATA = 127

enum class MidiMessageType {
    // NONE leaves a control silent, which is how a mapping is switched off
    NONE, NOTE, CONTROL_CHANGE, PROGRAM_CHANGE, PITCH_BEND
}

@Serializable
data class MidiMapping(
    // the identifier of a control pad item, suffixed with a direction or axis
    // for items which expose more than one control (see midiTargetsOf)
    val target: String,
    val messageType: MidiMessageType = MidiMessageType.CONTROL_CHANGE,
    val channel: Int = MIDI_MIN_CHANNEL,
    // note number for NOTE, controller number for CONTROL_CHANGE, program
    // number for PROGRAM_CHANGE, unused for PITCH_BEND
    val number: Int = MIDI_MIN_DATA
)

@Serializable
data class MidiConfig(
    // MidiDeviceInfo exposes no stable address, so the reported name is the
    // only handle we can persist
    val deviceName: String = "",
    val portIndex: Int = 0,
    val mappings: List<MidiMapping> = emptyList()
){

    fun toJson() = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String) = Json.decodeFromString<MidiConfig>(json)
    }

    val address get() = deviceName.ifEmpty { "No Device" }
}
