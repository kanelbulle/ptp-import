package com.github.kanelbulle.ptpimport.ui.main

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.kanelbulle.ptpimport.CopyService
import com.github.kanelbulle.ptpimport.extension.sumByLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.IllegalStateException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var connection: UsbDeviceConnection? = null
    private var mtpDevice: MtpDevice? = null
    val transferProgress: MutableLiveData<TransferProgress>
    private var deviceName: String? = null
    val deviceFiles: MutableLiveData<List<DeviceFile>>

    init {
        deviceFiles = MutableLiveData()
        transferProgress = MutableLiveData()
    }

    override fun onCleared() {
        mtpDevice?.close()
        connection?.close()
        super.onCleared()
    }

    // Connected device
    private val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"

    fun refreshConnectedDevices() = viewModelScope.launch {
        refreshConnectedDevicesInternal()
    }

    private suspend fun refreshConnectedDevicesInternal() = withContext(
        Dispatchers.IO
    ) {
        val manager =
            getApplication<Application>().getSystemService(Context.USB_SERVICE) as UsbManager

        val deviceList = manager.deviceList
        if (deviceList.isNotEmpty()) {
            val device = deviceList.values.first()
            deviceName = device.deviceName
            if (manager.hasPermission(device)) {
                loadFiles(manager, device)
            } else {
                requestDevicePermission(manager, device)
            }
        } else {
            Log.i("qwerty", "No connected devices")
        }
    }

    private fun loadFiles(
        manager: UsbManager,
        device1: UsbDevice
    ) {
        connection = manager.openDevice(device1)
        mtpDevice = MtpDevice(device1)
        connection?.let { mtpDevice?.open(it) }
        val storageIds = mtpDevice?.getStorageIds()
        if (storageIds == null) {
            return
        }
        val objectHandles = ArrayList<Int>()
        storageIds.forEach {
            val mtpDevice = this.mtpDevice
            if (mtpDevice != null) {
                val oh = mtpDevice.getObjectHandles(it, 0, 0) as IntArray
                oh.toCollection(objectHandles)
            }
        }

        val deviceFiles: ArrayList<DeviceFile> = ArrayList()
        objectHandles.forEach {
            val mtpDevice = this.mtpDevice
            if (mtpDevice != null) {
                val info: MtpObjectInfo = mtpDevice.getObjectInfo(it) as MtpObjectInfo
                deviceFiles.add(DeviceFile(it, info.name, info.compressedSizeLong))
            }
        }

        Log.i("qwerty", "deviceFiles: $deviceFiles")
        this.deviceFiles.postValue(deviceFiles)
    }

    private fun requestDevicePermission(manager: UsbManager, device: UsbDevice) {
        Log.i("qwerty", "requestDevicePermission")
        val permissionIntent =
            PendingIntent.getBroadcast(getApplication(), 0, Intent(ACTION_USB_PERMISSION), 0)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        getApplication<Application>().registerReceiver(usbReceiver, filter)
        manager.requestPermission(device, permissionIntent)
    }

    fun hasDestinationDirectorySet(): Boolean {
        return getDestinationDirectory() != null
    }

    fun setDestinationDirectory(it: Uri) {
        val sharedPreferences =
            getApplication<Application>().getSharedPreferences("main", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString("destination", it.toString()).apply()
    }

    fun getDestinationDirectory(): String? {
        val sharedPreferences =
            getApplication<Application>().getSharedPreferences("main", Context.MODE_PRIVATE)
        return sharedPreferences.getString("destination", null)
    }

    @SuppressLint("InvalidWakeLockTag")
    fun copyFilesToDestination(deviceFiles: List<DeviceFile>) {
        val destination = getDestinationDirectory()
        if (destination == null) {
            throw IllegalStateException("Must set a destination directory before copying files")
        }

        if (deviceName != null) {
            CopyService.startForegroundCopyService(
                getApplication(),
                deviceName!!,
                destination,
                deviceFiles.map { it.objectHandle },
                deviceFiles.sumByLong { it.sizeBytes })
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            Log.d("qwerty", "device connected! $device")
                            refreshConnectedDevices()
                        }
                    } else {
                        Toast.makeText(
                            getApplication(),
                            "Permission denied for USB access. This application requires USB permission to operate.",
                            Toast.LENGTH_SHORT
                        )
                        Log.d("qwerty", "permission denied for device $device")
                    }
                }
            }
        }
    }
    // Files on device
}
