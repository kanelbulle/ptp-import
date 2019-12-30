package com.github.kanelbulle.ptpimport.ui.main

data class TransferProgress(
    val currentFile: DeviceFile,
    val totalFiles: Long,
    val totalBytes: Long,
    val currentFilesTransferred: Long,
    val currentBytesTransferred: Long
){

}
