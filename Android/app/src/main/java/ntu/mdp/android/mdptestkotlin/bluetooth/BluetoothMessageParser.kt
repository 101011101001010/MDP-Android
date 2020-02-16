package ntu.mdp.android.mdptestkotlin.bluetooth

import android.util.Log
import ntu.mdp.android.mdptestkotlin.App
import ntu.mdp.android.mdptestkotlin.App.Companion.COMMAND_DIVIDER
import ntu.mdp.android.mdptestkotlin.App.Companion.COMMAND_PREFIX
import ntu.mdp.android.mdptestkotlin.App.Companion.DESCRIPTOR_DIVIDER
import ntu.mdp.android.mdptestkotlin.App.Companion.GRID_IDENTIFIER
import ntu.mdp.android.mdptestkotlin.App.Companion.ROBOT_POSITION_IDENTIFIER
import ntu.mdp.android.mdptestkotlin.App.Companion.ROBOT_STATUS_IDENTIFIER
import ntu.mdp.android.mdptestkotlin.App.Companion.SET_IMAGE_IDENTIFIER
import ntu.mdp.android.mdptestkotlin.App.Companion.usingAmd
import ntu.mdp.android.mdptestkotlin.arena.ArenaMap

class BluetoothMessageParser(private val callback: (status: MessageStatus, message: String) -> Unit) {
    enum class MessageStatus {
        ROBOT_STATUS,
        ROBOT_POSITION,
        IMAGE_POSITION,
        ARENA,
        GARBAGE,
        INFO
    }

    private var previousMessage: String = ""

    fun parse(message: String) {
        if (!message.contains(COMMAND_DIVIDER) || !message.contains(COMMAND_PREFIX)) {
            callback(MessageStatus.GARBAGE, message)
            return
        }

        val s: ArrayList<String> = ArrayList(message.split(COMMAND_DIVIDER))

        if (s.size != 2) {
            callback(MessageStatus.GARBAGE, "Something went wrong.")
            return
        }

        if ((App.autoUpdateArena || ArenaMap.isWaitingUpdate) && s[0] == "${COMMAND_PREFIX}${GRID_IDENTIFIER}") {
            ArenaMap.isWaitingUpdate = false

            if (usingAmd) {
                var s2: String = "f".padEnd(75, 'f')
                s2 = "${s2}${DESCRIPTOR_DIVIDER}${s[1]}"
                Log.e("TEST", s[1])
                Log.e("TEST", s2)
                callback(MessageStatus.ARENA, s2)
            } else {
                callback(MessageStatus.ARENA, s[1])
            }

            return
        }

        if (s[0] == "${COMMAND_PREFIX}${ROBOT_POSITION_IDENTIFIER}") {
            if (s[1] == previousMessage) return
            previousMessage = s[1]

            val s1 = s[1].split(", ")

            if (s1.size != 3) {
                callback(MessageStatus.INFO, "Something went wrong.")
                return
            }

            try {
                val x = if (usingAmd) s1[0].toInt() + 1 else s1[0].toInt()
                val y = if (usingAmd) s1[1].toInt() - 1 else s1[1].toInt()
                val r = s1[2].toInt()
                s[1] = "$x, $y, $r"
            }  catch (e: NumberFormatException) {
                Log.e(this::class.simpleName ?: "-", "Something went wrong.", e)
                callback(MessageStatus.INFO, "Something went wrong.")
                return
            }
        }

        when (s[0]) {
            "${COMMAND_PREFIX}${ROBOT_POSITION_IDENTIFIER}" -> callback(MessageStatus.ROBOT_POSITION, s[1])
            "${COMMAND_PREFIX}${ROBOT_STATUS_IDENTIFIER}" -> callback(MessageStatus.ROBOT_STATUS, s[1])
            "${COMMAND_PREFIX}${SET_IMAGE_IDENTIFIER}" -> callback(MessageStatus.IMAGE_POSITION, s[1])
            else -> callback(MessageStatus.GARBAGE, s[1])
        }
    }
}