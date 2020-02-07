package ntu.mdp.android.mdptestkotlin.arena

import android.content.Context
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ntu.mdp.android.mdptestkotlin.App.Companion.PAD_MOVABLE
import ntu.mdp.android.mdptestkotlin.App.Companion.TILT_MOVABLE
import ntu.mdp.android.mdptestkotlin.App.Companion.accelerometer
import ntu.mdp.android.mdptestkotlin.R
import ntu.mdp.android.mdptestkotlin.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.roundToInt

class ManualController(private val context: Context, binding: ActivityMainBinding, private val robotController: RobotController, private val callback: (callback: ArenaV2.Callback, message: String) -> Unit) {

    private val buttonList      : List<View?> = listOf(binding.padForwardButton, binding.padReverseButton, binding.padLeftButton, binding.padRightButton)
    private val forwardRect     : Rect = Rect(75, 10, 165, 90)
    private val reverseRect     : Rect = Rect(75, 150, 165, 230)
    private val leftRect        : Rect = Rect(15, 90, 95, 170)
    private val rightRect       : Rect = Rect(150, 90, 230, 170)
    private var swipeMode       : Boolean = false
    private var swipeOriginX    : Float = 0.0f
    private var swipeOriginY    : Float = 0.0f
    private var trackMovement   : Boolean = false
    private var currentDirection: ArenaV2.Direction = ArenaV2.Direction.NONE
    private lateinit var movementThread  : MovementThread

    val touchListener = View.OnTouchListener { view, event ->
        Log.e("EVENT", "$event")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return@OnTouchListener handleTouchDown(view, event)
            MotionEvent.ACTION_MOVE -> handleTouchMove(event)
            MotionEvent.ACTION_UP -> handleTouchUp()
        }

        return@OnTouchListener true
    }

    //create listener
    val gyroscopeSensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent?) {
            if (!TILT_MOVABLE) {
                return
            }

            if (event != null) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                when {
                    (z > 9 && y < 2.5) -> {
                        TILT_MOVABLE = false
                        pressPadButton(ArenaV2.Direction.FORWARD)
                        robotController.moveOrTurnRobot(0)
                    }

                    x > 4.5 -> {
                        TILT_MOVABLE = false
                        pressPadButton(ArenaV2.Direction.LEFT)
                        robotController.moveOrTurnRobot(270)
                    }

                    x < -4 -> {
                        TILT_MOVABLE = false
                        pressPadButton(ArenaV2.Direction.RIGHT)
                        robotController.moveOrTurnRobot(90)
                    }

                    (y > 9.5 && z < 3.5) -> {
                        TILT_MOVABLE = false
                        pressPadButton(ArenaV2.Direction.REVERSE)
                        robotController.moveOrTurnRobot(180)
                    }

                    else -> {
                        releasePadButtons()
                        robotController.cancelLast()
                    }
                }

                Log.e("XYZ values", "${event.values[0]}, ${event.values[1]}, ${event.values[2]}")
            }
        }
    }

    init {
        robotController.registerForBroadcast { _, _ ->
            if (accelerometer) TILT_MOVABLE = true
            else PAD_MOVABLE = true
        }
    }

    private fun handleTouchDown(view: View, event: MotionEvent): Boolean {
        if (!PAD_MOVABLE) return true
        if (swipeMode) {
            swipeOriginX = (view.width / 2.0f)
            swipeOriginY = (view.height / 2.0f)

            val x = (event.x - swipeOriginX)
            val y = (event.y - swipeOriginY)
            val threshold = 33

            when {
                (y < -threshold && abs(y) > abs(x)) -> updateCurrentDirection(ArenaV2.Direction.FORWARD)
                (y > threshold && abs(y) > abs(x)) -> updateCurrentDirection(ArenaV2.Direction.REVERSE)
                (x < -threshold && abs(y) < abs(x))  -> updateCurrentDirection(ArenaV2.Direction.LEFT)
                (x > threshold && abs(y) < abs(x)) -> updateCurrentDirection(ArenaV2.Direction.RIGHT)
            }
        } else {
            checkTouchIntersect(event)
        }

        if (!trackMovement) {
            if (::movementThread.isInitialized && movementThread.isAlive) movementThread.end()
            movementThread = MovementThread()
            movementThread.start()
            trackMovement = true
        }

        return true
    }

    private fun handleTouchMove(event: MotionEvent) {
        if (!PAD_MOVABLE) return
        Log.e("TEST", "TEST")

        if (swipeMode) {
            val x = (event.x - swipeOriginX)
            val y = (event.y - swipeOriginY)
            val threshold = 33

            when {
                (y < -threshold && abs(y) > abs(x)) -> updateCurrentDirection(ArenaV2.Direction.FORWARD)
                (y > threshold && abs(y) > abs(x)) -> updateCurrentDirection(ArenaV2.Direction.REVERSE)
                (x < -threshold && abs(y) < abs(x))  -> updateCurrentDirection(ArenaV2.Direction.LEFT)
                (x > threshold && abs(y) < abs(x)) -> updateCurrentDirection(ArenaV2.Direction.RIGHT)
            }
        } else {
            checkTouchIntersect(event)
        }

        if (!trackMovement) {
            if (::movementThread.isInitialized && movementThread.isAlive) movementThread.end()
            movementThread = MovementThread()
            movementThread.start()
            trackMovement = true
        }
    }

    private fun handleTouchUp() {
        robotController.cancelLast()
        callback(ArenaV2.Callback.UPDATE_STATUS, context.getString(R.string.idle))
        updateCurrentDirection(ArenaV2.Direction.NONE)
        if (::movementThread.isInitialized) movementThread.end()
        if (!swipeMode) releasePadButtons()
    }

    private fun checkTouchIntersect(event: MotionEvent) {
        when {
            forwardRect.contains(event.x.roundToInt(), event.y.roundToInt()) -> pressPadButton(ArenaV2.Direction.FORWARD)
            reverseRect.contains(event.x.roundToInt(), event.y.roundToInt()) -> pressPadButton(ArenaV2.Direction.REVERSE)
            leftRect.contains(event.x.roundToInt(), event.y.roundToInt()) -> pressPadButton(ArenaV2.Direction.LEFT)
            rightRect.contains(event.x.roundToInt(), event.y.roundToInt()) -> pressPadButton(ArenaV2.Direction.RIGHT)
            else -> releasePadButtons()
        }
    }

    @Synchronized
    private fun updateCurrentDirection(direction: ArenaV2.Direction) {
        if (currentDirection != direction) {
            robotController.cancelLast()
            this.currentDirection = direction
        }
    }

    private fun pressPadButton(direction: ArenaV2.Direction) {
        if (this.currentDirection != direction) {
            updateCurrentDirection(direction)

            val skipIndex: Int = when (direction) {
                ArenaV2.Direction.FORWARD -> 0
                ArenaV2.Direction.REVERSE -> 1
                ArenaV2.Direction.LEFT -> 2
                ArenaV2.Direction.RIGHT -> 3
                ArenaV2.Direction.NONE -> -1
            }

            for ((i, button) in buttonList.withIndex()) {
                if (i == skipIndex) dispatchTouchEvent(button, MotionEvent.ACTION_DOWN)
                else dispatchTouchEvent(button, MotionEvent.ACTION_UP)
            }
        }
    }

    private fun releasePadButtons() {
        updateCurrentDirection(ArenaV2.Direction.NONE)
        buttonList.forEach { dispatchTouchEvent(it, MotionEvent.ACTION_UP) }
    }

    private fun dispatchTouchEvent(view: View?, action: Int) {
        view?.dispatchTouchEvent(MotionEvent.obtain(1, 1, action, 0.0f, 0.0f, 0))
        view?.isEnabled = action != MotionEvent.ACTION_DOWN
    }

    fun reset() {
        releasePadButtons()
    }

    fun toggleSwipeMode(state: Boolean = !swipeMode) {
        swipeMode = state
    }

    private inner class MovementThread: Thread() {
        fun end() {
            trackMovement = false
        }

        override fun run() {
            CoroutineScope(Dispatchers.Default).launch {
                while (trackMovement) {
                    if (!PAD_MOVABLE) {
                        delay(1)
                        continue
                    }

                    val facing = when (currentDirection) {
                        ArenaV2.Direction.FORWARD -> 0
                        ArenaV2.Direction.REVERSE -> 180
                        ArenaV2.Direction.LEFT -> 270
                        ArenaV2.Direction.RIGHT -> 90
                        ArenaV2.Direction.NONE -> -1
                    }

                    if (facing == -1) continue
                    val currentFacing: Int = robotController.getRobotFacing()
                    val facingOffset: Int = currentFacing - facing
                    PAD_MOVABLE = false

                    if (facing == currentFacing || abs(facing - currentFacing) == 180) {
                        robotController.moveRobot(facing)
                    } else if (facingOffset == 90 || facingOffset == -270) {
                        robotController.turnRobot(Math.floorMod(currentFacing - 90, 360))
                    } else if (facingOffset == -90 || facingOffset == 270) {
                        robotController.turnRobot(Math.floorMod(currentFacing + 90, 360))
                    }

                    end()
                    return@launch
                    //if (BluetoothController.isSocketConnected()) delay(App.simulationDelay)
                }
            }
        }
    }
}