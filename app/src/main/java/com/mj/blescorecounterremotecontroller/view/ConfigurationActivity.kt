package com.mj.blescorecounterremotecontroller.view

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mj.blescorecounterremotecontroller.BleScoreCounterApp
import com.mj.blescorecounterremotecontroller.ConnectionManager
import com.mj.blescorecounterremotecontroller.ConnectionManager.isConnected
import com.mj.blescorecounterremotecontroller.Constants
import com.mj.blescorecounterremotecontroller.R
import com.mj.blescorecounterremotecontroller.broadcastreceiver.BtStateChangedReceiver
import com.mj.blescorecounterremotecontroller.databinding.ActivityConfigurationBinding
import com.mj.blescorecounterremotecontroller.listener.BtBroadcastListener
import com.mj.blescorecounterremotecontroller.listener.ConnectionEventListener
import com.mj.blescorecounterremotecontroller.model.BleDisplayCfg
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConfigurationActivity : AppCompatActivity() {

    private lateinit var  activityBinding: ActivityConfigurationBinding
    private var writableDisplayChar: BluetoothGattCharacteristic? = null

    private var msgBuffer: String = ""

    private val btStateChangedReceiver = BtStateChangedReceiver()


    private val app: BleScoreCounterApp by lazy {
        application as BleScoreCounterApp
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onMtuChanged = { bleDevice, _ ->
                runOnUiThread {
                    app.bleDisplay = bleDevice
                    app.shouldTryConnect = false
                    app.saveLastDeviceAddress(bleDevice.address)
                    enableCfgButtons()
                }
            }
            onCharacteristicChanged = { bleDevice, characteristic, value ->
                runOnUiThread {
                    val decoded = value.toString(Charsets.US_ASCII)
                    msgBuffer += decoded

                    val msgBufferLen = msgBuffer.length
                    if (msgBufferLen >= 2 && msgBuffer[msgBufferLen-2] == '\r' &&
                        msgBuffer[msgBufferLen-1] == '\n') {
                        // Full message received, process it
                        val msg = msgBuffer.removeSuffix(Constants.CRLF)
                        Log.d(Constants.BT_TAG, "Full message: $msg")

                        if (msg.startsWith(Constants.CONFIG_CMD_PREFIX)) {
                            val jsonStr = msg.removePrefix(Constants.CONFIG_CMD_PREFIX)
                            try {
                                val bleDisplayCfg = Json.decodeFromString<BleDisplayCfg>(jsonStr)

                                // Prevent calling the listeners and sending commands back
                                // to the BLE display.
                                activityBinding.showScoreSwitch.setOnCheckedChangeListener(null)
                                activityBinding.showDateSwitch.setOnCheckedChangeListener(null)
                                activityBinding.showTimeSwitch.setOnCheckedChangeListener(null)
                                activityBinding.scrollRb.setOnClickListener(null)
                                activityBinding.alternateRb.setOnClickListener(null)

                                // Update the UI.
                                activityBinding.brightnessSlider.value = bleDisplayCfg.brightness
                                    .toFloat()
                                activityBinding.showScoreSwitch.isChecked = bleDisplayCfg.useScore
                                activityBinding.showDateSwitch.isChecked = bleDisplayCfg.useDate
                                activityBinding.showTimeSwitch.isChecked = bleDisplayCfg.useTime
                                if (bleDisplayCfg.scroll) {
                                    activityBinding.scrollRb.isChecked = true
                                }
                                else {
                                    activityBinding.alternateRb.isChecked = true
                                }

                                // Activate the listeners again.
                                activityBinding.showScoreSwitch.setOnCheckedChangeListener(
                                    onShowScoreCheckedChangeListener)
                                activityBinding.showDateSwitch.setOnCheckedChangeListener(
                                    onShowDateCheckedChangeListener)
                                activityBinding.showTimeSwitch.setOnCheckedChangeListener(
                                    onShowTimeCheckedChangeListener)
                                activityBinding.scrollRb.setOnClickListener(onScrollClickedListener)
                                activityBinding.alternateRb.setOnClickListener(
                                    onAlternateClickedListener)
                            }
                            catch (ex: Exception) {
                                when (ex) {
                                    is SerializationException,
                                    is IllegalArgumentException -> {
                                        Log.e(Constants.BT_TAG, "Problem decoding JSON from " +
                                                Constants.CONFIG_CMD_PREFIX, ex
                                        )
                                    }
                                    else -> {
                                        Log.e(Constants.BT_TAG, "Problem with " +
                                                Constants.CONFIG_CMD_PREFIX, ex
                                        )
                                    }
                                }
                            }
                        }

                        // Reset the buffer
                        msgBuffer = ""
                    }
                }
            }
            onDisconnect = { bleDevice ->
                runOnUiThread {
                    disableCfgButtons()
                    app.startReconnectionCoroutine()
                }
            }
        }
    }

    private val btBroadcastListener by lazy {
        BtBroadcastListener().apply {
            onBluetoothOff = {
                runOnUiThread {
                    ConnectionManager.disconnectAllDevices()
                }
            }
            onBluetoothOn = {
                runOnUiThread {
                    if (app.bleDisplay != null) {
                        app.startReconnectionCoroutine()
                    } else {
                        app.startConnectionToLastDeviceCoroutine()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.activityBinding = ActivityConfigurationBinding.inflate(layoutInflater)

        val view = activityBinding.root
        setContentView(view)

        if (app.bleDisplay != null) {
            writableDisplayChar = ConnectionManager.findCharacteristic(
                app.bleDisplay!!, Constants.DISPLAY_WRITABLE_CHARACTERISTIC_UUID
            )
            if (writableDisplayChar != null) {
                ConnectionManager.writeCharacteristic(
                    app.bleDisplay!!, writableDisplayChar!!,
                    (Constants.GET_CONFIG_CMD + Constants.CRLF).toByteArray(Charsets.US_ASCII)
                )
            }
        }

        activityBinding.brightnessSlider.addOnChangeListener { slider, value, fromUser ->
            // Send command through BLE only when the change was initiated by the user.
            // Do not send it if it was changed programmatically.
            if (fromUser) {
                if (app.bleDisplay != null && writableDisplayChar != null) {
                    ConnectionManager.writeCharacteristic(
                        app.bleDisplay!!, writableDisplayChar!!,
                        (Constants.SET_BRIGHTNESS_CMD_PREFIX + value.toInt() + Constants.CRLF).
                            toByteArray(Charsets.US_ASCII)
                    )
                }
            }
        }

        activityBinding.showScoreSwitch.setOnCheckedChangeListener(onShowScoreCheckedChangeListener)
        activityBinding.showDateSwitch.setOnCheckedChangeListener(onShowDateCheckedChangeListener)
        activityBinding.showTimeSwitch.setOnCheckedChangeListener(onShowTimeCheckedChangeListener)
        activityBinding.alternateRb.setOnClickListener(onAlternateClickedListener)
        activityBinding.scrollRb.setOnClickListener(onScrollClickedListener)

        activityBinding.persistCfgBtn.setOnClickListener {
            val config = BleDisplayCfg()
            config.brightness = activityBinding.brightnessSlider.value.toInt()
            config.useScore = activityBinding.showScoreSwitch.isChecked
            config.useDate = activityBinding.showDateSwitch.isChecked
            config.useTime = activityBinding.showTimeSwitch.isChecked
            config.scroll = activityBinding.scrollRb.isChecked

            val json = Json { encodeDefaults = true }
            if (app.bleDisplay != null && writableDisplayChar != null) {
                ConnectionManager.writeCharacteristic(
                    app.bleDisplay!!, writableDisplayChar!!,
                    (Constants.PERSIST_CONFIG_CMD_PREFIX + json.encodeToString(config) +
                            Constants.CRLF).toByteArray(Charsets.US_ASCII)
                )
            }

            this.finish()
        }

        activityBinding.returnFromCfgBtn.setOnClickListener {
            this.finish()
        }
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }

        ConnectionManager.registerListener(connectionEventListener)

        this.registerReceiver(btStateChangedReceiver, filter)
        btStateChangedReceiver.registerListener(btBroadcastListener)

        if (app.bleDisplay == null || !app.bleDisplay!!.isConnected()) {
            disableCfgButtons()
        }
    }

    override fun onStop() {
        super.onStop()

        btStateChangedReceiver.unregisterListener(btBroadcastListener)
        this.unregisterReceiver(btStateChangedReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()

        ConnectionManager.unregisterListener(connectionEventListener)
    }

    private val onShowScoreCheckedChangeListener =
            CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (app.bleDisplay != null && writableDisplayChar != null) {
            val showScoreVal = if (isChecked) 1 else 0
            ConnectionManager.writeCharacteristic(app.bleDisplay!!, writableDisplayChar!!,
                (Constants.SET_SHOW_SCORE_CMD_PREFIX + showScoreVal + Constants.CRLF).toByteArray(
                    Charsets.US_ASCII
                )
            )
        }
    }

    private val onShowDateCheckedChangeListener =
            CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (app.bleDisplay != null && writableDisplayChar != null) {
            val showDateVal = if (isChecked) 1 else 0
            ConnectionManager.writeCharacteristic(
                app.bleDisplay!!, writableDisplayChar!!,
                (Constants.SET_SHOW_DATE_CMD_PREFIX + showDateVal + Constants.CRLF).
                    toByteArray(Charsets.US_ASCII)
            )
        }
    }

    private val onShowTimeCheckedChangeListener =
            CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (app.bleDisplay != null && writableDisplayChar != null) {
            val showTimeVal = if (isChecked) 1 else 0
            ConnectionManager.writeCharacteristic(
                app.bleDisplay!!, writableDisplayChar!!,
                (Constants.SET_SHOW_TIME_CMD_PREFIX + showTimeVal + Constants.CRLF).
                    toByteArray(Charsets.US_ASCII)
            )
        }
    }

    private val onAlternateClickedListener = View.OnClickListener {
        if (app.bleDisplay != null && writableDisplayChar != null) {
            ConnectionManager.writeCharacteristic(
                app.bleDisplay!!, writableDisplayChar!!,
                (Constants.SET_SCROLL_CMD_PREFIX + 0 + Constants.CRLF).toByteArray(Charsets.US_ASCII)
            )
        }
    }

    private val onScrollClickedListener = View.OnClickListener {
        if (app.bleDisplay != null && writableDisplayChar != null) {
            ConnectionManager.writeCharacteristic(
                app.bleDisplay!!, writableDisplayChar!!,
                (Constants.SET_SCROLL_CMD_PREFIX + 1 + Constants.CRLF).toByteArray(Charsets.US_ASCII)
            )
        }
    }

    private fun disableCfgButtons() {
        activityBinding.brightnessSlider.isEnabled = false
        activityBinding.showScoreSwitch.isEnabled = false
        activityBinding.showDateSwitch.isEnabled = false
        activityBinding.showTimeSwitch.isEnabled = false
        activityBinding.alternateRb.isEnabled = false
        activityBinding.scrollRb.isEnabled = false
        activityBinding.persistCfgBtn.isEnabled = false

        activityBinding.persistCfgBtn.backgroundTintList = ContextCompat.getColorStateList(
            this, R.color.disabled
        )
    }

    private fun enableCfgButtons() {
        activityBinding.brightnessSlider.isEnabled = true
        activityBinding.showScoreSwitch.isEnabled = true
        activityBinding.showDateSwitch.isEnabled = true
        activityBinding.showTimeSwitch.isEnabled = true
        activityBinding.alternateRb.isEnabled = true
        activityBinding.scrollRb.isEnabled = true
        activityBinding.persistCfgBtn.isEnabled = true

        activityBinding.persistCfgBtn.backgroundTintList = ContextCompat.getColorStateList(
            this, R.color.confirm_btn
        )
    }
}