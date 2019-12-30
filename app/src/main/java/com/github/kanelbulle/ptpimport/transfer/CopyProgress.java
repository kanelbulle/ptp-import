package com.github.kanelbulle.ptpimport.transfer;

public abstract class CopyProgress {

    public abstract void onFileCopySucceeded();

    public abstract void onFileCopyFailed(String fileName);

    public abstract void onFileCopySkipped(String fileName);

    public abstract void onBytesCopied(long bytesCopied);
}
