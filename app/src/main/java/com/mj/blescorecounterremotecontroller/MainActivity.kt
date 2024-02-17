package com.mj.blescorecounterremotecontroller

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mj.blescorecounterremotecontroller.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {
    private val btAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothManager.adapter
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onConnect = {
                mainBinding.btButton.setImageResource(R.drawable.bluetooth_connected)
            }
            onDisconnect = {
                mainBinding.btButton.setImageResource(R.drawable.bluetooth)
                Toast.makeText(this@MainActivity, "Disconnected from ${it.address}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // TODO add filtering on name
//    private val scanFilter = ScanFilter.Builder()
//        .build()
//
//    private val scanSettings = ScanSettings.Builder()
//        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//        // TODO Enable after testing W/ and W/O
////        .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
//        .build()
//
//    private val scanCallback = object : ScanCallback() {
//        @SuppressLint("MissingPermission")
//        override fun onScanResult(callbackType: Int, result: ScanResult) {
//            super.onScanResult(callbackType, result)
//
//            if (scanResults.none { it.device.address == result.device.address
//                        // TODO remove the name comparison, when filtering on name applied
//                        && it.device.name == result.device.name
//            }) {
//                with(result.device) {
//                    var msg = "Found BLE device! Name: ${name ?: "Unnamed"}, address: $address, UUIDS:"
//                    // TODO Maybe change uuids to result.scanRecord.serviceUuids
//                    uuids.forEachIndexed { i, parcelUUid ->
//                        msg += "\n${i+1}: ${parcelUUid.uuid}"
//                    }
//                    Log.i(Constants.BT_TAG, msg)
//                }
//                scanResults.add(result)
//            }
//        }
//
//        override fun onScanFailed(errorCode: Int) {
//            Log.e(Constants.BT_TAG, "onScanFailed: code $errorCode")
//        }
//    }

//    private val scanResults = mutableListOf<ScanResult>()
    private val btStateChangedReceiver = BtStateChangedReceiver()

//    private var scanning = false
    private var permissionsPermanentlyDenied = false

    private lateinit var activityResultLauncher: ActivityResultLauncher<Intent>
//    private lateinit var bleScanner: BluetoothLeScanner
    private lateinit var mainBinding: ActivityMainBinding


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.mainBinding = ActivityMainBinding.inflate(layoutInflater)
        val view = mainBinding.root

        setContentView(view)
//        setContentView(R.layout.activity_main)

        this.registerActivityForResult()

        ConnectionManager.registerListener(connectionEventListener)

        mainBinding.btButton.setOnClickListener {
            this.onBtButtonClick()
        }

        mainBinding.btButton.setOnLongClickListener {
            // TODO disconnect only specific device(s)
            ConnectionManager.disconnectAllDevices()
            true
        }
    }

    override fun onStart() {
        super.onStart()

        this.registerReceiver(btStateChangedReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStop() {
        super.onStop()

        this.unregisterReceiver(btStateChangedReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()

        ConnectionManager.unregisterListener(connectionEventListener)
    }

    /**
     * If some permissions were denied, show a rationale with explanation why the permissions are
     * needed and ask for them again.
     *
     * If some permissions were permanently denied, explain to the users that the app cannot work
     * without them and navigate users to app settings to manually enable them.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == Constants.BT_PERMISSIONS_REQUEST_CODE) {
            val containsPermanentDenial = permissions
                .zip(grantResults.toTypedArray())
                .any {
                    it.second == PackageManager.PERMISSION_DENIED &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(this, it.first)
                }
            val containsDenial = grantResults.any { it == PackageManager.PERMISSION_DENIED }
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            when {
                containsPermanentDenial -> {
                    this.permissionsPermanentlyDenied = true
                    this.showStepsToManuallyEnablePermissions()
                }
                containsDenial -> {
                    this.showRequestBtPermissionsRationale()
                }
                allGranted -> {
                    this.displayBtFragment()
                }
            }
        }
    }

    private fun onBtButtonClick() {
        if (this.isBleSupported()) {

            if (this.hasBtPermissions()) {
//                    this.promptEnableBluetooth()
                this.displayBtFragment()
            }
            else {
                this.requestBtPermissions()
//                if (this.hasBtPermissions()) {
//                    btFragment.show(supportFragmentManager, "BluetoothFragment")
//                }
//                    this.promptEnableBluetooth()
            }
        }
        else {
            Toast.makeText(this, "Bluetooth Low Energy is not supported on this " +
                    "device", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayBtFragment() {
        val btFragment = BluetoothFragment.newInstance(false)
        btFragment.show(supportFragmentManager, "BluetoothFragment")
    }

    // TODO use when clicking on BT button
    private fun isBleSupported(): Boolean {
        return this.btAdapter != null
                && packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    private fun registerActivityForResult() {
        this.activityResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.i(Constants.BT_TAG, "Enable Bluetooth activity result OK")
            } else {
                Log.i(Constants.BT_TAG, "Enable Bluetooth activity result DENIED")
            }
        }
    }

//    private fun promptEnableBluetooth() {
//        if (this.btAdapter == null) {
//            Log.i(Constants.BT_TAG, "BluetoothAdapter is null")
//        }
//        else {
//            if (!this.btAdapter!!.isEnabled) {
//                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
//
//                if (this.hasBtPermissions()) {
//                    this.activityResultLauncher.launch(enableBtIntent)
//                }
//                else {
//                    this.requestBtPermissions()
//                }
//            }
//        }
//    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasBtPermissions(): Boolean =
        hasPermission(Manifest.permission.BLUETOOTH_SCAN)
                && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun requestBtPermissions() {
        ActivityCompat.requestPermissions(this,
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            Constants.BT_PERMISSIONS_REQUEST_CODE
        )
    }

    private fun showRequestBtPermissionsRationale() {
        val alertBuilder = AlertDialog.Builder(this)

        with(alertBuilder)
        {
            setTitle("Bluetooth permission required")
            setMessage("Starting from Android 12, the system requires apps to be granted " +
                    "Bluetooth access in order to scan for and connect to BLE devices.")
            setPositiveButton("OK") { _,_ -> requestBtPermissions() }
            setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            setCancelable(false)
            show()
        }
    }

    private fun showStepsToManuallyEnablePermissions() {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth permissions were permanently denied!")
            .setMessage("Please, navigate to App Settings and manually grant Bluetooth " +
                    "permissions to allow connection to BLE Score Counter.")
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }

}