package com.github.umer0586.droidpad.data.util.midi

data class MidiDeviceDescriptor(
    val name: String,
    val inputPortCount: Int,
    val transport: String
)

// a port DroidPad writes MIDI messages to, owned by the caller of openInputPort
interface MidiPort {
    fun send(message: ByteArray)

    /**
     * Wires the device's output port back to DroidPad, delivering raw bytes in
     * whatever chunks the device produces. Returns false when the device sends
     * nothing back, which is normal for a one way destination.
     */
    fun listen(onBytes: (ByteArray, Int, Int) -> Unit): Boolean

    fun close()
}

sealed interface MidiPortResult {
    data class Opened(val port: MidiPort) : MidiPortResult
    data object DeviceNotFound : MidiPortResult
    data object OpenFailed : MidiPortResult
}

interface MidiUtil {
    val isMidiSupported: Boolean
    // only devices which accept input, a control pad has nothing to do with the rest
    fun availableDevices(): List<MidiDeviceDescriptor>
    suspend fun openInputPort(deviceName: String, portIndex: Int): MidiPortResult
}
