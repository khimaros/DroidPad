package com.github.umer0586.droidpad.data

import com.github.umer0586.droidpad.data.database.entities.ItemType
import com.github.umer0586.droidpad.ui.components.DPAD_BUTTON
import com.github.umer0586.droidpad.ui.components.LEDSTATE
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json


private val JsonCon = Json {
    encodeDefaults = true
}

// states reported by momentary controls (BUTTON and DPAD)
const val PRESS_STATE = "PRESS"
const val RELEASE_STATE = "RELEASE"
const val CLICK_STATE = "CLICK"

// an interaction travelling from the control pad towards the connection
sealed interface ControlPadEvent {
    val id: String
    fun toJson(): String
    fun toCsv(): String
}

@Serializable
data class SliderEvent(
    override val id: String,
    val type: ItemType = ItemType.SLIDER,
    val value: Float
): ControlPadEvent {
    override fun toJson(): String {
        return JsonCon.encodeToString(this)
    }
    override fun toCsv() = "$id,SLIDER,$value"

    companion object {
        fun fromJson(json: String): SliderEvent {
            return JsonCon.decodeFromString(json)
        }
    }
}

@Serializable
data class SwitchEvent(
    override val id: String,
    val type: ItemType = ItemType.SWITCH,
    val state: Boolean
): ControlPadEvent {
    override fun toJson(): String {
        return JsonCon.encodeToString(this)
    }
    override fun toCsv() = "$id,SWITCH,$state"

    companion object {
        fun fromJson(json: String): SwitchEvent {
            return JsonCon.decodeFromString(json)
        }
    }
}

@Serializable
data class ButtonEvent(
    override val id: String,
    val type: ItemType = ItemType.BUTTON,
    val state: String
): ControlPadEvent {
    override fun toJson(): String {
        return JsonCon.encodeToString(this)
    }
    override fun toCsv() = "$id,BUTTON,$state"
}

@Serializable
data class DPadEvent(
    override val id: String,
    val type: ItemType = ItemType.DPAD,
    val button: DPAD_BUTTON,
    val state: String
): ControlPadEvent {
    override fun toJson(): String {
        return JsonCon.encodeToString(this)
    }
    override fun toCsv() = "$id,DPAD,$button,$state"
}

@Serializable
data class JoyStickEvent(
    override val id: String,
    val type: ItemType = ItemType.JOYSTICK,
    val x: Float,
    val y: Float
): ControlPadEvent {
    override fun toJson(): String {
        return JsonCon.encodeToString(this)
    }
    override fun toCsv() = "$id,JOYSTICK,$x,$y"
}

@Serializable
data class SteeringWheelEvent(
    override val id: String,
    val type: ItemType = ItemType.STEERING_WHEEL,
    val angle: Float
): ControlPadEvent {
    override fun toJson(): String {
        return JsonCon.encodeToString(this)
    }
    override fun toCsv() = "$id,STEERING_WHEEL,$angle"
}

@Serializable
data class LedEvent(
    val id: String,
    val type: ItemType = ItemType.LED,
    val state: LEDSTATE
){
    fun toJson(): String {
        return JsonCon.encodeToString(this)
    }

    companion object {
        fun fromJson(json: String): LedEvent {
            return JsonCon.decodeFromString(json)
        }
    }
}

@Serializable
data class LogEvent(
    @Transient val timestamp: String = "",
    val type: String = "LOG",
    val message: String
){
    companion object {
        fun fromJson(json: String): LogEvent {
            return JsonCon.decodeFromString(json)
        }
    }
}

@Serializable
data class GaugeEvent(
    val id: String,
    val type: ItemType = ItemType.GAUGE,
    val value: Float
){
    fun toJson(): String {
        return JsonCon.encodeToString(this)
    }

    companion object {
        fun fromJson(json: String): GaugeEvent {
            return JsonCon.decodeFromString(json)
        }
    }
}
