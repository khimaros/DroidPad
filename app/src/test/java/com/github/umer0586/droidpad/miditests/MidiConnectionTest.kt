package com.github.umer0586.droidpad.miditests

import com.github.umer0586.droidpad.data.connection.ConnectionState
import com.github.umer0586.droidpad.data.connection.MidiConnection
import com.github.umer0586.droidpad.data.connectionconfig.MidiConfig
import com.github.umer0586.droidpad.data.database.entities.ConnectionType
import com.github.umer0586.droidpad.data.util.midi.MidiDeviceDescriptor
import com.github.umer0586.droidpad.data.util.midi.MidiPort
import com.github.umer0586.droidpad.data.util.midi.MidiPortResult
import com.github.umer0586.droidpad.data.util.midi.MidiUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

private class FakeMidiPort(
    val failOnSend: Boolean = false,
    private val sendsBack: Boolean = true
) : MidiPort {
    val sent = mutableListOf<ByteArray>()
    var closed = false
    private var listener: ((ByteArray, Int, Int) -> Unit)? = null

    override fun send(message: ByteArray) {
        if (failOnSend) throw IOException("port is gone")
        sent.add(message)
    }

    override fun listen(onBytes: (ByteArray, Int, Int) -> Unit): Boolean {
        if (!sendsBack) return false
        listener = onBytes
        return true
    }

    // stands in for the device pushing bytes from its own thread
    fun emit(vararg values: Int) {
        val bytes = ByteArray(values.size) { values[it].toByte() }
        listener?.invoke(bytes, 0, bytes.size)
    }

    override fun close() {
        closed = true
    }
}

private class FakeMidiUtil(
    override val isMidiSupported: Boolean = true,
    private val devices: List<MidiDeviceDescriptor> = emptyList(),
    private val port: FakeMidiPort? = FakeMidiPort(),
    private val openFails: Boolean = false
) : MidiUtil {

    var requestedDevice: String? = null
    var requestedPortIndex: Int? = null

    override fun availableDevices() = devices

    override suspend fun openInputPort(deviceName: String, portIndex: Int): MidiPortResult {
        requestedDevice = deviceName
        requestedPortIndex = portIndex

        if (openFails) return MidiPortResult.OpenFailed
        return port?.let { MidiPortResult.Opened(it) } ?: MidiPortResult.DeviceNotFound
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class MidiConnectionTest {

    @Test
    fun `opening the configured port reports a connected state`() = runTest {
        val port = FakeMidiPort()
        val midiUtil = FakeMidiUtil(port = port)
        val connection = MidiConnection(
            midiUtil = midiUtil,
            midiConfig = MidiConfig(deviceName = "Android USB Peripheral Port", portIndex = 1),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()

        assertEquals(ConnectionType.MIDI, connection.connectionType)
        assertEquals(ConnectionState.MIDI_CONNECTED, connection.connectionState.value)
        assertEquals("Android USB Peripheral Port", midiUtil.requestedDevice)
        assertEquals(1, midiUtil.requestedPortIndex)
    }

    @Test
    fun `a missing device is reported instead of connecting`() = runTest {
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(port = null),
            midiConfig = MidiConfig(deviceName = "Unplugged"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()

        assertEquals(ConnectionState.MIDI_DEVICE_NOT_FOUND, connection.connectionState.value)
    }

    @Test
    fun `a device that refuses to open is reported as a failed connection`() = runTest {
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(openFails = true),
            midiConfig = MidiConfig(deviceName = "Busy"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()

        assertEquals(ConnectionState.MIDI_CONNECTION_FAILED, connection.connectionState.value)
    }

    @Test
    fun `a device without midi support never attempts to open a port`() = runTest {
        val midiUtil = FakeMidiUtil(isMidiSupported = false)
        val connection = MidiConnection(
            midiUtil = midiUtil,
            midiConfig = MidiConfig(deviceName = "Anything"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()

        assertEquals(ConnectionState.MIDI_NOT_SUPPORTED, connection.connectionState.value)
        assertEquals(null, midiUtil.requestedDevice)
    }

    @Test
    fun `binary payloads reach the port untouched`() = runTest {
        val port = FakeMidiPort()
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(port = port),
            midiConfig = MidiConfig(deviceName = "Synth"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()
        connection.sendData(byteArrayOf(0x90.toByte(), 60, 127))

        assertEquals(1, port.sent.size)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 127), port.sent.single())
    }

    @Test
    fun `text payloads are dropped because they have no midi representation`() = runTest {
        val port = FakeMidiPort()
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(port = port),
            midiConfig = MidiConfig(deviceName = "Synth"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()
        connection.sendData("""{"type":"ACCELEROMETER","x":0.1,"y":0.2,"z":9.8}""")

        assertTrue(port.sent.isEmpty())
    }

    @Test
    fun `a write failure is surfaced as a send error`() = runTest {
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(port = FakeMidiPort(failOnSend = true)),
            midiConfig = MidiConfig(deviceName = "Synth"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()
        connection.sendData(byteArrayOf(0x90.toByte(), 60, 127))

        assertEquals(ConnectionState.MIDI_SEND_FAILED, connection.connectionState.value)
    }

    @Test
    fun `reconnecting closes the port the previous attempt opened`() = runTest {
        val first = FakeMidiPort()
        val second = FakeMidiPort()
        val ports = ArrayDeque(listOf(first, second))

        val midiUtil = object : MidiUtil {
            override val isMidiSupported = true
            override fun availableDevices() = emptyList<MidiDeviceDescriptor>()
            override suspend fun openInputPort(deviceName: String, portIndex: Int) =
                MidiPortResult.Opened(ports.removeFirst())
        }

        val connection = MidiConnection(
            midiUtil = midiUtil,
            midiConfig = MidiConfig(deviceName = "Synth"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()
        connection.setup()
        connection.sendData(byteArrayOf(0x90.toByte(), 60, 127))

        assertTrue(first.closed)
        assertTrue(first.sent.isEmpty())
        assertEquals(1, second.sent.size)
    }

    @Test
    fun `bytes arriving from the device are framed into whole messages`() = runTest {
        val port = FakeMidiPort()
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(port = port),
            midiConfig = MidiConfig(deviceName = "Synth"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope
        )

        connection.setup()

        val received = mutableListOf<ByteArray>()
        val collecting = backgroundScope.launch { connection.receivedBytes.collect { received.add(it) } }
        runCurrent()

        // one whole message, then a second split across two deliveries
        port.emit(0xB0, 0x07, 0x40)
        port.emit(0x90, 0x3C)
        port.emit(0x7F)
        runCurrent()

        assertEquals(2, received.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 0x07, 0x40), received[0])
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x3C, 0x7F), received[1])
        collecting.cancel()
    }

    @Test
    fun `a device that sends nothing back still connects`() = runTest {
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(port = FakeMidiPort(sendsBack = false)),
            midiConfig = MidiConfig(deviceName = "One way"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope
        )

        connection.setup()

        assertEquals(ConnectionState.MIDI_CONNECTED, connection.connectionState.value)
    }

    @Test
    fun `tearing down closes the port and stops further writes`() = runTest {
        val port = FakeMidiPort()
        val connection = MidiConnection(
            midiUtil = FakeMidiUtil(port = port),
            midiConfig = MidiConfig(deviceName = "Synth"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        connection.setup()
        connection.tearDown()
        connection.sendData(byteArrayOf(0x90.toByte(), 60, 127))

        assertTrue(port.closed)
        assertTrue(port.sent.isEmpty())
        assertEquals(ConnectionState.MIDI_DISCONNECTED, connection.connectionState.value)
    }
}
