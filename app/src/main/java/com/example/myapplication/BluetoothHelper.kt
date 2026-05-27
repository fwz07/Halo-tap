package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothHelper(private val context: Context, private val bluetoothAdapter: BluetoothAdapter?) {

    interface SyncCallback {
        fun onSyncStarted()
        fun onSyncSuccess()
        fun onSyncFailure(error: String)
    }

    private var bluetoothSocket: BluetoothSocket? = null

    fun syncDataToDevice(parent: String, child: String, sos: String, callback: SyncCallback) {
        val dataToSync = "*$parent|$child|$sos#"
        callback.onSyncStarted()
        
        thread {
            var syncSuccessful = false
            var errorMessage = "Connection failed"

            try {
                val pairedDevices = bluetoothAdapter?.bondedDevices
                val device = pairedDevices?.find {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return@find false
                    }
                    it.name?.contains("halotap", ignoreCase = true) == true
                }

                if (device != null) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Handler(Looper.getMainLooper()).post {
                            callback.onSyncFailure("Missing Bluetooth Permission")
                        }
                        return@thread
                    }

                    val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                    bluetoothSocket?.connect()

                    val outputStream = bluetoothSocket?.outputStream
                    val inputStream = bluetoothSocket?.inputStream

                    outputStream?.write(dataToSync.toByteArray())
                    outputStream?.flush()

                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val response = reader.readLine()
                    
                    if (response?.contains("SETUP_SUCCESS") == true) {
                        syncSuccessful = true
                    } else {
                        errorMessage = "Device received data but failed to confirm"
                    }

                    Thread.sleep(500)
                    bluetoothSocket?.close()
                } else {
                    errorMessage = "HaloTap device not paired"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "BT Error: ${e.message}"
                try { bluetoothSocket?.close() } catch (_: Exception) {}
            }

            Handler(Looper.getMainLooper()).post {
                if (syncSuccessful) {
                    callback.onSyncSuccess()
                } else {
                    callback.onSyncFailure(errorMessage)
                }
            }
        }
    }
}
