package com.github.umer0586.droidpad.data.util.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TRANSPORT_USB = "USB"
private const val TRANSPORT_VIRTUAL = "VIRTUAL"
private const val TRANSPORT_BLUETOOTH = "BLUETOOTH"
private const val TRANSPORT_UNKNOWN = "UNKNOWN"

class MidiUtilImp(private val applicationContext: Context) : MidiUtil {

    private val tag = javaClass.simpleName

    private val midiManager =
        applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    override val isMidiSupported: Boolean
        get() = midiManager != null &&
                applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

    override fun availableDevices() = inputDevices().map {
        MidiDeviceDescriptor(
            name = it.displayName,
            inputPortCount = it.inputPortCount,
            transport = it.transportName
        )
    }

    override suspend fun openInputPort(deviceName: String, portIndex: Int): MidiPortResult {

        val manager = midiManager ?: return MidiPortResult.DeviceNotFound
        val deviceInfo = inputDevices().firstOrNull { it.displayName == deviceName }
            ?: return MidiPortResult.DeviceNotFound

        // MidiService owns the USB device, so opening it needs no USB permission
        val device = suspendCancellableCoroutine<MidiDevice?> { continuation ->
            manager.openDevice(
                deviceInfo,
                { openedDevice ->
                    // the caller may be gone by the time the device opens, and
                    // a device nobody resumes with would stay open forever
                    if (continuation.isActive) continuation.resume(openedDevice)
                    else openedDevice?.closeQuietly()
                },
                Handler(Looper.getMainLooper())
            )
        } ?: return MidiPortResult.OpenFailed

        val port = try {
            device.openInputPort(portIndex.coerceIn(0, deviceInfo.inputPortCount - 1))
        } catch (e: Exception) {
            Log.e(tag, "failed to open input port of $deviceName", e)
            null
        }

        if (port == null) {
            device.closeQuietly()
            return MidiPortResult.OpenFailed
        }

        return MidiPortResult.Opened(AndroidMidiPort(device, port, portIndex.coerceAtLeast(0)))
    }

    // getDevicesForTransport() would avoid the deprecation but needs API 33,
    // and getDevices() still enumerates every MIDI 1.0 device
    @Suppress("DEPRECATION")
    private fun inputDevices() =
        midiManager?.devices.orEmpty().filter { it.inputPortCount > 0 }
}

private class AndroidMidiPort(
    private val device: MidiDevice,
    private val port: MidiInputPort,
    private val portIndex: Int
) : MidiPort {

    private var outputPort: MidiOutputPort? = null

    override fun send(message: ByteArray) = port.send(message, 0, message.size)

    override fun listen(onBytes: (ByteArray, Int, Int) -> Unit): Boolean {

        if (portIndex >= device.info.outputPortCount) return false

        val opened = try {
            device.openOutputPort(portIndex)
        } catch (e: Exception) {
            Log.e("MidiPort", "failed to open output port $portIndex", e)
            null
        } ?: return false

        opened.connect(object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                onBytes(msg, offset, count)
            }
        })

        outputPort = opened
        return true
    }

    override fun close() {
        try {
            outputPort?.close()
            port.close()
        } finally {
            device.closeQuietly()
        }
    }
}

private fun MidiDevice.closeQuietly() {
    try {
        close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// USB MIDI devices rarely populate every property, so fall back through the
// ones they do set before giving up and using the service assigned id
private val MidiDeviceInfo.displayName: String
    get() = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        ?: listOfNotNull(
            properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
            properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        ).joinToString(" ").ifBlank { "MIDI device $id" }

private val MidiDeviceInfo.transportName: String
    get() = when (type) {
        MidiDeviceInfo.TYPE_USB -> TRANSPORT_USB
        MidiDeviceInfo.TYPE_VIRTUAL -> TRANSPORT_VIRTUAL
        MidiDeviceInfo.TYPE_BLUETOOTH -> TRANSPORT_BLUETOOTH
        else -> TRANSPORT_UNKNOWN
    }
