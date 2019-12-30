package com.github.kanelbulle.ptpimport

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.net.Uri
import android.text.format.Formatter
import androidx.documentfile.provider.DocumentFile
import com.github.kanelbulle.ptpimport.transfer.CopyProgress
import com.github.kanelbulle.ptpimport.transfer.copyHandleToDestination
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class CopyService : IntentService("CopyService") {

    companion object {
        private const val NOTIFICATION_PROGRESS_ID: Int = 7
        private const val NOTIFICATION_COMPLETED_ID: Int = 8

        val EXTRA_DESTINATION = "destination"
        val EXTRA_DEVICE_NAME = "device_name"
        val EXTRA_TOTAL_SIZE_BYTES = "total_size_bytes"
        val EXTRA_OBJECT_HANDLES = "object_handles"

        fun startForegroundCopyService(
            application: Application,
            deviceName: String,
            destination: String,
            objectHandles: List<Int>,
            totalSizeBytes: Long
        ) {
            val copyIntent = Intent(application, CopyService::class.java)
            copyIntent.putExtra(CopyService.EXTRA_DEVICE_NAME, deviceName)
            copyIntent.putExtra(CopyService.EXTRA_DESTINATION, destination)
            copyIntent.putExtra(CopyService.EXTRA_TOTAL_SIZE_BYTES, totalSizeBytes)
            copyIntent.putIntegerArrayListExtra(
                CopyService.EXTRA_OBJECT_HANDLES,
                ArrayList<Int>(objectHandles)
            )
            application.startForegroundService(copyIntent)
        }
    }

    private var totalFiles: Long = 0
    private var totalSizeBytes: Long = 0

    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) {
            return
        }

        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)!!
        val destination = intent.getStringExtra(EXTRA_DESTINATION)!!
        val objectHandles = intent.getIntegerArrayListExtra(EXTRA_OBJECT_HANDLES)!!.map { it }
        totalSizeBytes = intent.getLongExtra(EXTRA_TOTAL_SIZE_BYTES, 0)
        totalFiles = objectHandles.size.toLong()

        createNotificationChannels()
        val notification = createProgressNotification(totalFiles, 0, 0)
        startForeground(NOTIFICATION_PROGRESS_ID, notification)

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = manager.deviceList.get(deviceName)
        if (usbDevice == null) {
            showErrorNotification(getString(R.string.copy_error_device_not_found_notification_message))
            return
        }

        val usbDeviceConnection = manager.openDevice(usbDevice)
        if (usbDeviceConnection == null) {
            showErrorNotification(getString(R.string.copy_error_failed_connecting_to_device_notification_message))
            return
        }

        val mtpDevice = MtpDevice(usbDevice)
        if (!mtpDevice.open(usbDeviceConnection)) {
            showErrorNotification(getString(R.string.copy_error_failed_connecting_via_ptp_notification_message))
            return
        }

        val destinationDirectory =
            // Only null on lollipop and earlier, no need to check
            DocumentFile.fromTreeUri(getApplication(), Uri.parse(destination))!!

        copyObjectHandlesToDestination(mtpDevice, objectHandles, destinationDirectory)

        stopForeground(true)
    }

    private fun createNotificationChannels() {
        // TODO: Channels should be defined elsewhere, and not repeatedly created
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel("default", "Default", NotificationManager.IMPORTANCE_DEFAULT)
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createProgressNotification(
        numFiles: Long,
        numFilesCopied: Long,
        numBytesCopied: Long
    ): Notification {
        val bytesCopied: String = Formatter.formatShortFileSize(this, numBytesCopied)
        val bytesToCopy: String = Formatter.formatShortFileSize(this, totalSizeBytes)
        return Notification.Builder(this, "default")
            .setContentTitle(getString(R.string.notification_title_copying_files_progress))
            .setContentText("$numFilesCopied / ${numFiles}, $bytesCopied / $bytesToCopy")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun showProgressNotification(
        numFiles: Long,
        numFilesCopied: Long,
        numBytesCopied: Long
    ) {
        val notification = createProgressNotification(numFiles, numFilesCopied, numBytesCopied)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_PROGRESS_ID, notification)
    }

    private fun showCompletionNotification(
        numFilesCopied: Long,
        numFilesFailed: Long,
        numFilesSkipped: Long,
        dataTransferred: Long,
        failedFileNames: MutableList<String>,
        skippedFileNames: MutableList<String>
    ) {
        val bytesCopied: String = Formatter.formatShortFileSize(this, dataTransferred)
        val failedFileNamesMsg = failedFileNames.joinToString(", ")
        val skippedFileNamesMsg = skippedFileNames.joinToString(", ")
        val notification: Notification = Notification.Builder(this, "default")
            .setContentTitle(getString(R.string.notification_title_copy_complete))
            .setStyle(Notification.BigTextStyle().bigText("$numFilesCopied files transferred, $numFilesFailed failed, $numFilesSkipped skipped. Total $bytesCopied copied.\nFailed files: ${failedFileNamesMsg}\nSkipped files: $skippedFileNamesMsg"))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOnlyAlertOnce(true)
            .build()
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_COMPLETED_ID, notification)
    }

    private fun showErrorNotification(msg: String) {
        val notification: Notification = Notification.Builder(this, "default")
            .setContentTitle(getString(R.string.notification_title_failed_copying_files))
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOnlyAlertOnce(true)
            .build()
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_COMPLETED_ID, notification)
    }

    private fun copyObjectHandlesToDestination(
        mtpDevice: MtpDevice,
        objectHandles: List<Int>,
        destinationDirectory: DocumentFile
    ) {
        val buffer = ByteArray(32_000_000)

        var totalFilesCopied: Long = 0
        var totalFilesFailed: Long = 0
        var totalFilesSkipped: Long = 0
        var totalBytesCopied: Long = 0
        val failedFileNames: MutableList<String> = mutableListOf()
        val skippedFileNames: MutableList<String> = mutableListOf()

        val copyProgress = object : CopyProgress() {
            override fun onFileCopySucceeded() {
                totalFilesCopied++
                updateNotification()
            }

            override fun onFileCopyFailed(fileName: String) {
                totalFilesFailed++
                failedFileNames.add(fileName)
                updateNotification()
            }

            override fun onFileCopySkipped(fileName: String) {
                totalFilesSkipped++
                skippedFileNames.add(fileName)
                updateNotification()
            }

            override fun onBytesCopied(bytesCopied: Long) {
                totalBytesCopied += bytesCopied
                updateNotification()
            }

            private fun updateNotification() {
                showProgressNotification(totalFiles, totalFilesCopied, totalBytesCopied)
            }
        }

        val pool = Executors.newFixedThreadPool(4)
        val dispatcher = pool.asCoroutineDispatcher()

        runBlocking {
            coroutineScope {
                objectHandles.forEach {
                    launch(dispatcher) {
                        copyHandleToDestination(
                            this@CopyService,
                            mtpDevice,
                            it,
                            destinationDirectory,
                            buffer,
                            copyProgress
                        )
                    }
                }
            }
        }

        pool.shutdown()

        showCompletionNotification(
            totalFilesCopied,
            totalFilesFailed,
            totalFilesSkipped,
            totalBytesCopied,
            failedFileNames,
            skippedFileNames
        )
    }

}