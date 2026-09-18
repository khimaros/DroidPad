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

package com.github.umer0586.droidpad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.umer0586.droidpad.data.connectionconfig.MidiMapping
import com.github.umer0586.droidpad.data.connectionconfig.MidiMessageType
import com.github.umer0586.droidpad.data.util.midi.MidiTarget
import com.github.umer0586.droidpad.ui.theme.DroidPadTheme

// two of these plus the gap have to fit inside the card
private val NUMBER_FIELD_WIDTH = 120.dp

/**
 * Edits what a single control of the pad sends over MIDI. The caller owns the
 * mapping, so out of range input is clamped upstream and reflected back here.
 */
@Composable
fun MidiMappingEditor(
    modifier: Modifier = Modifier,
    target: MidiTarget,
    mapping: MidiMapping,
    onMappingChange: (MidiMapping) -> Unit
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                modifier = Modifier.fillMaxWidth(),
                text = target.label,
                style = MaterialTheme.typography.titleSmall
            )

            EnumDropdown<MidiMessageType>(
                modifier = Modifier.fillMaxWidth(),
                selectedValue = mapping.messageType,
                label = "Message",
                onValueSelected = { onMappingChange(mapping.copy(messageType = it)) }
            )

            if (mapping.messageType != MidiMessageType.NONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                    NumberField(
                        label = "Channel",
                        value = mapping.channel,
                        onValueChange = { onMappingChange(mapping.copy(channel = it)) }
                    )

                    // pitch bend addresses the whole channel, it carries no number
                    if (mapping.messageType != MidiMessageType.PITCH_BEND) {
                        NumberField(
                            label = mapping.messageType.numberLabel,
                            value = mapping.number,
                            onValueChange = { onMappingChange(mapping.copy(number = it)) }
                        )
                    }
                }
            }
        }
    }
}

// re-keying on the value lets a clamped or reset mapping flow back into the field
@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        modifier = Modifier.width(NUMBER_FIELD_WIDTH),
        value = text,
        singleLine = true,
        onValueChange = { newText ->
            text = newText
            newText.toIntOrNull()?.also(onValueChange)
        },
        shape = RoundedCornerShape(50),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

private val MidiMessageType.numberLabel
    get() = when (this) {
        MidiMessageType.NOTE -> "Note"
        MidiMessageType.CONTROL_CHANGE -> "Controller"
        MidiMessageType.PROGRAM_CHANGE -> "Program"
        MidiMessageType.NONE, MidiMessageType.PITCH_BEND -> "Number"
    }

@Preview(showBackground = true)
@Composable
private fun MidiMappingEditorPreview() {
    DroidPadTheme {
        MidiMappingEditor(
            target = MidiTarget(key = "pad.UP", label = "pad UP", continuous = false),
            mapping = MidiMapping(target = "pad.UP", messageType = MidiMessageType.NOTE, number = 36),
            onMappingChange = {}
        )
    }
}
