package ntu.mdp.android.mdptestkotlin.backup

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import ntu.mdp.android.mdptestkotlin.App
import ntu.mdp.android.mdptestkotlin.App.Companion.ANIMATOR_DURATION
import ntu.mdp.android.mdptestkotlin.App.Companion.MOVEMENT_PROCSES_INTERVAL
import ntu.mdp.android.mdptestkotlin.App.Companion.SEND_ARENA_COMMAND
import ntu.mdp.android.mdptestkotlin.App.Companion.appTheme
import ntu.mdp.android.mdptestkotlin.App.Companion.autoUpdateArena
import ntu.mdp.android.mdptestkotlin.App.Companion.isSimple
import ntu.mdp.android.mdptestkotlin.App.Companion.sharedPreferences
import ntu.mdp.android.mdptestkotlin.MainSimpleActivity
import ntu.mdp.android.mdptestkotlin.R
import ntu.mdp.android.mdptestkotlin.bluetooth.BluetoothController
import ntu.mdp.android.mdptestkotlin.databinding.ActivityMainBinding
import ntu.mdp.android.mdptestkotlin.settings.SettingsActivity
import ntu.mdp.android.mdptestkotlin.utils.ActivityUtil
import ntu.mdp.android.mdptestkotlin.bluetooth.BluetoothMessageParser
import ntu.mdp.android.mdptestkotlin.arena.ArenaController
import java.util.*
import kotlin.math.abs


class BackUpMainActivity : AppCompatActivity() {
    // Static variables that are accessed by other classes for control purposes.
    // May have a better way of doing it?
    companion object {
        var isUpdating = false
        var isPlotting = false
    }

    // Message control.
    private enum class MessageType {
        INCOMING,
        OUTGOING,
        SYSTEM
    }

    // Determines the final movement direction of the robot during simulation.
    private enum class MovementFlag {
        NONE,
        FORWARD,
        REVERSE,
        LEFT,
        RIGHT
    }

    private enum class Mode {
        NONE,
        EXPLORATION,
        FASTEST_PATH
    }

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var activityUtil: ActivityUtil
    private lateinit var binding: ActivityMainBinding

    private lateinit var timer: CountDownTimer
    private lateinit var arenaController: ArenaController
    private lateinit var bluetoothMessageParser: BluetoothMessageParser
    private lateinit var startFabList: List<FloatingActionButton>
    private lateinit var plotModeButtonList: List<View>
    private lateinit var viewList: List<View>

    private var isSadMode = false
    private var currentMode: Mode = Mode.NONE
    private var robotAutonomous = false
    private var startFabOpened = false
    private var isSwipeMode = false
    private var timerCounter = 0
    private var lastClickTime: Long = 0L
    private var continuousMovement = false
    private var continuousMovementFlag: MovementFlag = MovementFlag.NONE
    private var continuousOriginX = 0.0f
    private var continuousOriginY = 0.0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Sets the theme back to default as we changed the theme on startup to show the preview screen (the one with the app icon).
        setTheme(appTheme)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request for bluetooth permission explicitly.
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN), 1001)

        activityUtil = ActivityUtil(this)
        // Overlays a white opaque layout on the activity to hide all the loading and animation of views on startup.
        activityUtil.toggleProgressBar(View.VISIBLE, opaque = true, instant = true)

        // Terminates the activity (and app by that extent) if the device does not support bluetooth.
        if (bluetoothAdapter == null) {
            activityUtil.sendDialog(getString(R.string.error_bluetooth_not_supported), finish = true)
            return
        }

        isSadMode = sharedPreferences.getBoolean(getString(R.string.app_pref_sad_mode), false)
        if (isSadMode) {
            activityUtil.startActivity(MainSimpleActivity::class.java, startNew = true)
            return
        }

        // Do a quick closing animation on the floating action buttons (fab) to initialise their position when we open them.
        val animation: Animation = AnimationUtils.loadAnimation(applicationContext, R.anim.main_fab_close_init)
        startFabList = listOf(startExplorationFab, startFastestPathFab)
        for (fab in startFabList) fab.startAnimation(animation)

        // Will come in handy later to cycle through most of the views.
        viewList = listOf(settingsButton, startButton, plotButton, resetButton, autoManualButton, f1Button, f2Button, statusCard, modeCard, coordinatesCard, timerCard, messagesCard, messagesInputCard, controlPadCard)

        // Plot mode buttons that pop up only in plot mode.
        plotModeButtonList = listOf(plotObstacleButton, removeObstacleButton, clearObstacleButton, doneButton)

        // Overrides the static variable at application level if the grid map should auto update.
        // Toggles the auto / manual button text and icon as well.
        autoUpdateArena = sharedPreferences.getBoolean("AUTO_UPDATE", false)
        toggleAutoManualMode()
        isSimple = false

        // Initialises the arena controller and defines the behaviour of the callback function in a lambda expression.
        // Note that one of the arguments of ArenaController is a callback variable of 'Unit' type (a.k.a. higher order function).
        // Basically passes a function (with variables if necessary) as an argument to arena controller (used for callback).
        // Whenever the arena controller calls the function we passed it, the code in the curly braces {} will run.
        // In this case, the function 'callback: (status: Status, message: String)' is passed.
        // Therefore, there is a status variable and a message variable, and we can decide what to do with the message depending on the status.
        arenaController = ArenaController(this) { status, message ->
            when (status) {
                ArenaController.ArenaStatus.INFO -> activityUtil.sendSnack(message)
                ArenaController.ArenaStatus.WRITE -> sendCommand(message)
                ArenaController.ArenaStatus.ROBOT -> displayInChat(MessageType.INCOMING, message)
                ArenaController.ArenaStatus.COORDINATES -> coordinatesLabel.text = message
                ArenaController.ArenaStatus.STATUS -> statusLabel.text = message
                ArenaController.ArenaStatus.RESET -> resetArena()
            }
        }

        // Initialises the message parser similarly to the arena controller.
        bluetoothMessageParser = BluetoothMessageParser { status, message ->
            when (status) {
                BluetoothMessageParser.MessageStatus.GARBAGE -> displayInChat(MessageType.INCOMING, message)
                BluetoothMessageParser.MessageStatus.ARENA -> arenaController.updateArena(message)
                BluetoothMessageParser.MessageStatus.IMAGE_POSITION -> arenaController.updateImage(message)
                BluetoothMessageParser.MessageStatus.ROBOT_POSITION -> arenaController.updateRobot(message)
                BluetoothMessageParser.MessageStatus.ROBOT_STATUS -> statusLabel.text = message
                BluetoothMessageParser.MessageStatus.INFO -> activityUtil.sendSnack(message)
            }
        }

        // Assigns a touch listener to the D-Pad buttons and swipe layout for robot control.
        padForwardButton.setOnTouchListener(touchListener)
        padReverseButton.setOnTouchListener(touchListener)
        padLeftButton.setOnTouchListener(touchListener)
        padRightButton.setOnTouchListener(touchListener)
        swipePadLayout.setOnTouchListener(touchListener)
        messagesOutputEditText.setOnKeyListener(onEnter)

        // Need to wait for the fab closing animations to finish (from above) so we do a small hack here.
        // Launches a new coroutine (something like a new thread) and runs a delay of 100ms before switching back to the current context and hide the fabs.
        CoroutineScope(Dispatchers.Default).launch {
            delay(100)
            withContext(Dispatchers.Main) {
                for (fab in startFabList) {
                    fab.visibility = View.GONE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (bluetoothAdapter == null || isSadMode) return

        // If bluetooth is turned off, asks to turn it on.
        if (!bluetoothAdapter!!.isEnabled) startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1000)
    }

    override fun onResume() {
        super.onResume()
        if (bluetoothAdapter == null || isSadMode) return

        // Hide any opened fabs (presumably opened before switching activities).
        if (startFabOpened) toggleFabs()
        // Resets the texts on the custom buttons in case it is changed in the settings.
        f1Button.text = sharedPreferences.getString(getString(R.string.app_pref_label_f1), getString(R.string.f1_default))
        f2Button.text = sharedPreferences.getString(getString(R.string.app_pref_label_f2), getString(R.string.f2_default))

        // Checks the connection status and update the status label accordingly.
        statusLabel.text = if (BluetoothController.isSocketConnected()) getString(R.string.connected) else getString(R.string.disconnected)

        // If bluetooth is still turned off, warns the user that bluetooth features will be disabled (do the features really not run? idk).
        // Otherwise, starts listening for incoming connections.
        if (!bluetoothAdapter!!.isEnabled) activityUtil.sendSnack(getString(R.string.error_bluetooth_off))
        else startBluetoothListener()

        // Finally, hide the white overlay after a second delay (for any last minute finishing touches).
        CoroutineScope(Dispatchers.Default).launch {
            delay(1000)
            withContext(Dispatchers.Main) {
                activityUtil.toggleProgressBar(View.GONE)
            }
        }
    }

    override fun onBackPressed() {
        activityUtil.sendYesNoDialog(getString(R.string.exit_the_app), { positive -> if (positive) finish() })
    }

    fun clickUiButton(view: View) {
        // Clicks are processed only every X milliseconds as defined in App class.
        // Checks if delay is over before processing.
        if (!isClickDelayOver()) return

        when (view.id) {
            R.id.settingsButton -> activityUtil.startActivity(SettingsActivity::class.java)
            R.id.resetButton -> resetArena()
            R.id.messagesClearButton -> activityUtil.sendYesNoDialog(getString(R.string.clear_message_log), { positive -> if (positive) messagesTextView.text = "" })

            // Opens up the fabs for sub-choices when the start button is pressed.
            // If already started, pauses and resets the views back to normal.
            R.id.startButton -> {
                if (!robotAutonomous) {
                    toggleFabs()
                } else {
                    sendCommand(sharedPreferences.getString(getString(R.string.app_pref_pause), getString(
                        R.string.pause_default
                    ))!!)
                    onStartClicked()
                }
            }

            // Similar to start button.
            R.id.plotButton -> {
                if (isPlotting) {
                    arenaController.resetActions()
                }

                onPlotClicked()
            }

            R.id.autoManualButton -> {
                autoUpdateArena = !autoUpdateArena
                sharedPreferences.edit().putBoolean(getString(R.string.auto), autoUpdateArena).apply()
                toggleAutoManualMode()
            }

            // Sends the customised commands to the robot.
            // Checks if the entered commands are for forward and reverse (accidental maybe?).
            // If so, commands the robot to move as well.
            // Technically can be handled on Arduino side but there's also status updates and movement checks (should also be done on Arduino actually).
            R.id.f1Button, R.id.f2Button -> {
                val text = if (view.id == R.id.f1Button) sharedPreferences.getString(getString(R.string.app_pref_command_f1), getString(R.string.f1_default))
                else sharedPreferences.getString(getString(R.string.app_pref_command_f2), getString(R.string.f2_default))

                when (text) {
                    sharedPreferences.getString(getString(R.string.app_pref_forward), getString(
                        R.string.forward_default
                    )) -> {
                        arenaController.moveRobot(1, BluetoothController.isSocketConnected())
                        return
                    }

                    sharedPreferences.getString(getString(R.string.app_pref_reverse), getString(
                        R.string.reverse_default
                    )) -> {
                        arenaController.moveRobot(-1, BluetoothController.isSocketConnected())
                        return
                    }
                }

                sendCommand(text!!)
            }

            R.id.startExplorationFab -> {
                sendCommand(sharedPreferences.getString(getString(R.string.app_pref_exploration), getString(
                    R.string.exploration_default
                ))!!)
                currentMode = Mode.EXPLORATION
                onStartClicked()
                displayInChat(MessageType.SYSTEM, getString(R.string.started_something, "exploration."))
                toggleFabs()
            }

            R.id.startFastestPathFab -> {
                sendCommand(sharedPreferences.getString(getString(R.string.app_pref_fastest), getString(
                    R.string.fastest_path_default
                ))!!)
                currentMode = Mode.FASTEST_PATH
                onStartClicked()
                displayInChat(MessageType.SYSTEM, getString(R.string.started_something, "fastest path."))
                toggleFabs()
            }

            R.id.controlModeButton -> {
                isSwipeMode = !isSwipeMode
                val color: Int = if (isSwipeMode) R.color.colorAccent else android.R.color.holo_blue_light
                val visibility: Int = if (isSwipeMode) View.GONE else View.VISIBLE
                controlModeButton.setTextColor(getColor(color))
                padForwardButton.visibility = visibility
                padReverseButton.visibility = visibility
                padLeftButton.visibility = visibility
                padRightButton.visibility = visibility
            }

            R.id.doneButton -> onPlotClicked()
            R.id.clearObstacleButton -> arenaController.resetObstacles()

            R.id.plotObstacleButton -> {
                if (arenaController.plotMode != ArenaController.PlotMode.PLOT_OBSTACLE) {
                    plotModeButtonList.forEach { it.isEnabled = true }
                    view.isEnabled = false
                    arenaController.plotMode = ArenaController.PlotMode.PLOT_OBSTACLE
                }
            }

            R.id.removeObstacleButton -> {
                if (arenaController.plotMode != ArenaController.PlotMode.REMOVE_OBSTACLE) {
                    plotModeButtonList.forEach { it.isEnabled = true }
                    view.isEnabled = false
                    arenaController.plotMode = ArenaController.PlotMode.REMOVE_OBSTACLE
                }
            }
        }
    }

    // Originally, the D-Pad only processes each individual click for movement (no continuous movement by holding down the button).
    // This has since been superseded by the touch listener that allows for continuous movement (although somewhat wonky).
    // We keep it here for reference and in case we want to revert, but mark it as deprecated.
    @Deprecated("Superseded by onTouchListener for continuous movement.")
    fun clickControlPad(view: View) {
        if (!isClickDelayOver()) return

        when (view.id) {
            R.id.padForwardButton -> arenaController.moveRobot(1, BluetoothController.isSocketConnected())
            R.id.padReverseButton -> arenaController.moveRobot(-1, BluetoothController.isSocketConnected())
            R.id.padLeftButton -> arenaController.turnRobot(-1, BluetoothController.isSocketConnected())
            R.id.padRightButton -> arenaController.turnRobot(1, BluetoothController.isSocketConnected())
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun clickSendMessage(view: View) {
        if (!isClickDelayOver()) return
        val message: String = messagesOutputEditText.text.toString().trim()

        if (message.isNotBlank()) {
            sendCommand(message)
            messagesOutputEditText.setText("")
        }
    }

    private fun toggleFabs() {
        startFabOpened = !startFabOpened
        val animationId: Int = if (startFabOpened) R.anim.main_fab_open else R.anim.main_fab_close
        val animation: Animation =  AnimationUtils.loadAnimation(applicationContext, animationId)
        animation.duration = ANIMATOR_DURATION
        // Also determines the animation behaviours based on the state of the fabs.
        animation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationRepeat(p0: Animation?) {}

            override fun onAnimationStart(p0: Animation?) {
                if (startFabOpened) for (fab in startFabList) fab.visibility = View.VISIBLE
            }

            override fun onAnimationEnd(p0: Animation?) {
                if (!startFabOpened) for (fab in startFabList) fab.visibility = View.GONE
            }
        })

        for (fab in startFabList) fab.startAnimation(animation)
    }

    private fun toggleAutoManualMode() {
        if (autoUpdateArena) {
            autoManualButton.text = getString(R.string.auto)
            autoManualButton.icon = getDrawable(R.drawable.ic_auto)
        } else {
            autoManualButton.text = getString(R.string.manual)
            autoManualButton.icon = getDrawable(R.drawable.ic_manual)
        }
    }

    private fun startBluetoothListener() {
        // Starts the bluetooth server if the socket is not connected.
        // Otherwise, re-register the callback function and refreshes the arena.
        if (!BluetoothController.isSocketConnected()) {
            BluetoothController.startServer(callback)
        }
        else {
            BluetoothController.callback = callback
            isUpdating = true
            sendCommand(SEND_ARENA_COMMAND)
        }
    }

    private fun sendCommand(command: String): Boolean {
        displayInChat(MessageType.OUTGOING, command)

        if (!bluetoothAdapter!!.isEnabled) {
            activityUtil.sendSnack(getString(R.string.error_bluetooth_off))
            return false
        }

        if (!BluetoothController.isSocketConnected()) {
            activityUtil.sendSnack(getString(R.string.error_bluetooth_not_connected))
            return false
        }

        if (command.isNotEmpty()) {
            BluetoothController.write(command)
            return true
        }

        return false
    }

    private fun onStartClicked() {
        robotAutonomous = !robotAutonomous

        if (robotAutonomous) {
            startButton.text = getString(R.string.pause)
            startButton.icon = getDrawable(R.drawable.ic_pause)

            timer = object: CountDownTimer(Long.MAX_VALUE, 1000) {
                override fun onTick(p0: Long) {
                    timerCounter++
                    val seconds: Int = timerCounter % 60
                    val minutes: Int = Math.floorDiv(timerCounter, 60)
                    timerLabel.text = getString(R.string.timer_minute_second, minutes.toString().padStart(2, '0'), seconds.toString().padStart(2, '0'))
                }

                override fun onFinish() {
                    // Timer finished. Should not happen!
                }
            }

            timer.start()
        } else {
            startButton.text = getString(R.string.start)
            startButton.icon = getDrawable(R.drawable.ic_start)
            val type: String = if (currentMode == Mode.EXPLORATION) getString(R.string.exploration) else getString(R.string.fastest_path)
            displayInChat(MessageType.SYSTEM, "$type - ${timerLabel.text.toString().trim()}")
            timer.cancel()
            timerCounter = 0
            currentMode = Mode.NONE
        }

        setMode()
    }

    private fun onPlotClicked() {
        isPlotting = !isPlotting
        if (startFabOpened) toggleFabs()
        val firstList = if (isPlotting) viewList else plotModeButtonList // to hide
        val secondList = if (isPlotting) plotModeButtonList else viewList // to show

        activityUtil.scaleViews(firstList, false) {
            activityUtil.toggleProgressBar(View.VISIBLE, opaque = true) {
                val gridLayoutList: List<GridLayout> = arenaController.getGridLayouts()
                gridLayoutList.forEach {
                    it.pivotX = 0.0f
                    it.pivotY = 0.0f
                    it.scaleX = if (isPlotting) 1.44f else 1.0f
                    it.scaleY = if (isPlotting) 1.44f else 1.0f
                }

                activityUtil.toggleProgressBar(View.GONE) {
                    activityUtil.scaleViews(secondList, true)
                    if (!isPlotting) {
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(250)

                            withContext(Dispatchers.Main) {
                                messagesScrollView.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }
        }
    }

    // Message log to display commands sent and strings with no specific purpose (debug messages, etc.).
    private fun displayInChat(messageType: MessageType, message: String) {
        val prefixType: String =
            when (messageType) {
                MessageType.INCOMING -> getString(R.string.prefix_robot)
                MessageType.OUTGOING -> getString(R.string.prefix_tablet)
                MessageType.SYSTEM -> ""
            }

        val calendar: Calendar = Calendar.getInstance()
        val timeStamp = "${(calendar[Calendar.HOUR_OF_DAY]).toString().padStart(2, '0')}:${(calendar[Calendar.MINUTE]).toString().padStart(2, '0')}"

        val prefix: String = getString(R.string.chat_prefix, timeStamp, prefixType).trim()
        val displayMessage = "$prefix $message"
        val previousMessages = messagesTextView.text.toString().trim()
        val newMessage = if (previousMessages.isNotBlank()) "$previousMessages\n$displayMessage" else displayMessage
        messagesTextView.text = newMessage
        CoroutineScope(Dispatchers.Default).launch {
            delay(250)

            withContext(Dispatchers.Main) {
                messagesScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun connectionChanged(status: BluetoothController.Status) {
        if (status == BluetoothController.Status.CONNECTED) {
            statusLabel.text = getString(R.string.connected)
            isUpdating = true
            sendCommand(SEND_ARENA_COMMAND)
        } else {
            statusLabel.text = getString(R.string.disconnected)
            startBluetoothListener()
        }
    }

    private fun resetArena() {
        activityUtil.sendYesNoDialog(getString(R.string.reset_arena_timer), { positive ->
            if (positive) {
                arenaController.resetArena()
                timerLabel.text = getString(R.string.timer_default)
            }
        })
    }

    private fun isClickDelayOver(): Boolean {
        if (System.currentTimeMillis() - lastClickTime < App.BUTTON_CLICK_DELAY_INTERVAL) return false
        lastClickTime = System.currentTimeMillis()
        return true
    }

    private fun setMode() {
        when (currentMode) {
            Mode.NONE -> modeLabel.text = getString(R.string.none)
            Mode.EXPLORATION -> modeLabel.text = getString(R.string.exploration)
            Mode.FASTEST_PATH -> modeLabel.text = getString(R.string.fastest_path)
        }
    }

    private val callback: (status: BluetoothController.Status, message: String) -> Unit = { status, message ->
        when (status) {
            BluetoothController.Status.CONNECTED, BluetoothController.Status.DISCONNECTED -> {
                connectionChanged(status)
                activityUtil.sendSnack(message)
            }

            BluetoothController.Status.READ -> bluetoothMessageParser.parse(message)
            BluetoothController.Status.WRITE_SUCCESS -> Log.d(this::class.simpleName ?: "-", message)
            else -> activityUtil.sendSnack(message)
        }
    }

    private val touchListener = View.OnTouchListener { view, event ->
        if (isPlotting) {
            return@OnTouchListener true
        }

        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isSwipeMode) {
                    if (!continuousMovement) {
                        continuousOriginX = (view.width / 2.0f)
                        continuousOriginY = (view.height / 2.0f)
                        ContinuousMovementThread().start()
                        continuousMovement = true
                    }

                    return@OnTouchListener true
                }

                if (System.currentTimeMillis() - lastClickTime < 500) {
                    return@OnTouchListener false
                }

                lastClickTime = System.currentTimeMillis()

                if (!continuousMovement) {
                    when (view.id) {
                        R.id.padForwardButton -> continuousMovementFlag = MovementFlag.FORWARD
                        R.id.padReverseButton -> continuousMovementFlag = MovementFlag.REVERSE
                        R.id.padLeftButton -> continuousMovementFlag = MovementFlag.LEFT
                        R.id.padRightButton -> continuousMovementFlag = MovementFlag.RIGHT
                    }

                    ContinuousMovementThread().start()
                    continuousMovement = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (continuousMovement && isSwipeMode) {
                    val x = (event.x - continuousOriginX)
                    val y = (event.y - continuousOriginY)
                    val threshold = 33

                    when {
                        (y < -threshold && abs(y) > abs(x)) -> continuousMovementFlag = MovementFlag.FORWARD
                        (y > threshold && abs(y) > abs(x)) -> continuousMovementFlag = MovementFlag.REVERSE
                        (x < -threshold && abs(y) < abs(x))  -> continuousMovementFlag = MovementFlag.LEFT
                        (x > threshold && abs(y) < abs(x)) -> continuousMovementFlag = MovementFlag.RIGHT
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                continuousMovement = false
                statusLabel.text = if (BluetoothController.isSocketConnected()) getString(R.string.connected) else getString(R.string.disconnected)
                continuousMovementFlag = MovementFlag.NONE
                view?.performClick()
            }
        }

        false
    }

    private val onEnter = View.OnKeyListener { view, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
            val message: String = (view as EditText).text.toString().trim()

            if (message.isNotBlank()) {
                sendCommand(message)
                messagesOutputEditText.setText("")
            }
            return@OnKeyListener true
        }

        false
    }

    private inner class ContinuousMovementThread: Thread() {
        override fun run() {
            var lastMoveTime = 0L

            while (continuousMovement) {
                if (System.currentTimeMillis() - lastMoveTime >= MOVEMENT_PROCSES_INTERVAL) {
                    lastMoveTime = System.currentTimeMillis()

                    CoroutineScope(Dispatchers.Main).launch {
                        if (continuousMovementFlag == MovementFlag.NONE) {
                            return@launch
                        }

                        val requestedFacing: Int = when (continuousMovementFlag) {
                            MovementFlag.REVERSE -> 180
                            MovementFlag.LEFT -> 270
                            MovementFlag.RIGHT -> 90
                            else -> 0
                        }
                        val robotFacing = arenaController.getRobotFacing()
                        val facingOffset: Int = robotFacing - requestedFacing

                        if (requestedFacing == robotFacing) {
                            arenaController.moveRobot(1, BluetoothController.isSocketConnected())
                        } else if (abs(requestedFacing - robotFacing) == 180) {
                            arenaController.moveRobot(-1, BluetoothController.isSocketConnected())
                        } else if (facingOffset == 90 || facingOffset == -270) {
                            arenaController.turnRobot(-1, BluetoothController.isSocketConnected())
                        } else {
                            arenaController.turnRobot(1, BluetoothController.isSocketConnected())
                        }
                    }
                }
            }
        }
    }
}