package com.tiangua.download.util;

import android.os.Environment;
import android.os.StatFs;

import java.io.File;

public class DeviceUtil
{

    public static boolean existSDCard()
    {
        if (Environment.getExternalStorageState().equals(
                Environment.MEDIA_MOUNTED))
        {
            return true;
        }
        else
            return false;
    }

    /**
     * 获取手机sd卡剩余空间
     * 
     * @return
     */
    public static long getAvailaleSize()
    {

        File path = Environment.getExternalStorageDirectory(); // 取得sdcard文件路径

        StatFs stat = new StatFs(path.getPath());

        long blockSize = stat.getBlockSize();

        long availableBlocks = stat.getAvailableBlocks();

        return availableBlocks * blockSize;

        // (availableBlocks * blockSize)/1024 KIB 单位

        // (availableBlocks * blockSize)/1024 /1024 MIB单位
    }
}
