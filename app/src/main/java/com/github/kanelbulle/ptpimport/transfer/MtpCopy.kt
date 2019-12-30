package com.github.kanelbulle.ptpimport.transfer

import android.content.Context
import android.mtp.MtpDevice
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.google.common.io.Files
import java.io.IOException

const val TAG = "MtpCopy"

/**
 * Copies the file represented by the objectHandle to the specified destination over MTP.
 */
fun copyHandleToDestination(
    context: Context,
    mtpDevice: MtpDevice,
    objectHandle: Int,
    destinationDirectory: DocumentFile,
    buffer: ByteArray,
    copyProgress: CopyProgress
) {
    val info = mtpDevice.getObjectInfo(objectHandle)
    if (info == null) {
        Log.e(TAG, "Failed loading object info")
        copyProgress.onFileCopyFailed(info?.name)
        return
    }
    if (info.compressedSizeLong == 0L) {
        Log.e(TAG, "Failed copying file, (${info.name}) has zero size")
        copyProgress.onFileCopyFailed(info?.name)
        return
    }

    val fileExtension = Files.getFileExtension(info.name)
    var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)
    if (mimeType == null) {
        mimeType = "*/*"
    }

    Log.d(
        TAG,
        "Creating new file: ${info.name}, mimeType: $mimeType, sizeBytes: ${info.compressedSizeLong}"
    )
    val newFile = destinationDirectory.createFile(mimeType, info.name)
    if (newFile == null) {
        Log.e(TAG, "Failed creating file")
        copyProgress.onFileCopyFailed(info.name)
        return
    }
    if (newFile.exists() && newFile.length() == info.compressedSizeLong) {
        Log.i(TAG, "Skipping file")
        copyProgress.onFileCopySkipped(info.name)
        return
    }

    val outputStream = context.contentResolver.openOutputStream(newFile.uri)
    if (outputStream == null) {
        Log.e(TAG, "Failed opening outputstream")
        copyProgress.onFileCopyFailed(info.name)
        return
    }

    var bytesWritten: Long = 0
    var read: Long = 0
    var offset: Long = 0
    do {
        read = mtpDevice.getPartialObject(
            info.objectHandle, offset,
            buffer.size.toLong(), buffer
        )
        offset += read
        try {
            outputStream.write(buffer, 0, read.toInt())
            bytesWritten += read
            copyProgress.onBytesCopied(read)
        } catch (e: IOException) {
            copyProgress.onFileCopyFailed(info.name)
        }
    } while (read != 0L)

    if (bytesWritten == info.compressedSizeLong) {
        copyProgress.onFileCopySucceeded()
    } else {
        copyProgress.onFileCopyFailed(info.name)
        Log.i(
            TAG,
            "Failed copying complete file: bytesWritten: $bytesWritten, fileSize: ${info.compressedSizeLong}"
        )
    }
}
