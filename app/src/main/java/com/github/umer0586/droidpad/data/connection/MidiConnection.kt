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

package com.github.umer0586.droidpad.data.connection

import com.github.umer0586.droidpad.data.connectionconfig.MidiConfig
import com.github.umer0586.droidpad.data.database.entities.ConnectionType
import com.github.umer0586.droidpad.data.util.midi.MidiPort
import com.github.umer0586.droidpad.data.util.midi.MidiPortResult
import com.github.umer0586.droidpad.data.util.midi.MidiStreamParser
import com.github.umer0586.droidpad.data.util.midi.MidiUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes MIDI messages to an input port of the configured device. The device is
 * whatever the platform exposes, which covers a USB instrument attached in host
 * mode, the peripheral port when the phone is plugged into a computer in MIDI
 * mode, and virtual ports published by other apps.
 */
class MidiConnection(
    private val midiUtil: MidiUtil,
    val midiConfig: MidiConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
) : Connection() {

    private var port: MidiPort? = null

    override val connectionType: ConnectionType
        get() = ConnectionType.MIDI

    override suspend fun setup() {

        if (!midiUtil.isMidiSupported) {
            notifyConnectionState(ConnectionState.MIDI_NOT_SUPPORTED)
            return
        }

        // a repeated connect must not strand the port the previous one opened
        closePort()
        notifyConnectionState(ConnectionState.MIDI_CONNECTING)

        when (val result = midiUtil.openInputPort(midiConfig.deviceName, midiConfig.portIndex)) {
            is MidiPortResult.Opened -> {
                port = result.port
                listenForMidi(result.port)
                notifyConnectionState(ConnectionState.MIDI_CONNECTED)
            }

            MidiPortResult.DeviceNotFound -> notifyConnectionState(ConnectionState.MIDI_DEVICE_NOT_FOUND)
            MidiPortResult.OpenFailed -> notifyConnectionState(ConnectionState.MIDI_CONNECTION_FAILED)
        }
    }

    // text payloads (sensor readings) have no MIDI representation, so they are
    // dropped rather than smuggled onto the wire as raw bytes
    override suspend fun sendData(data: String) = Unit

    override suspend fun sendData(data: ByteArray) = withContext<Unit>(ioDispatcher) {
        try {
            port?.send(data)
        } catch (e: Exception) {
            e.printStackTrace()
            notifyConnectionState(ConnectionState.MIDI_SEND_FAILED)
        }
    }

    override suspend fun tearDown() = withContext<Unit>(ioDispatcher) {
        closePort()
        notifyConnectionState(ConnectionState.MIDI_DISCONNECTED)
    }

    // the device delivers arbitrary chunks on its own thread, so the bytes are
    // framed into whole messages here and handed to the flow from our scope
    private fun listenForMidi(port: MidiPort) {
        val parser = MidiStreamParser()

        port.listen { bytes, offset, count ->
            val messages = synchronized(parser) { parser.accept(bytes, offset, count) }

            if (messages.isNotEmpty()) {
                scope.launch {
                    messages.forEach { notifyReceivedBytes(it) }
                }
            }
        }
    }

    private fun closePort() {
        try {
            port?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        port = null
    }
}
