package com.tiangua.download.network.singledown;

import android.content.Context;

import com.tiangua.download.callback.DownloadProgressListener;

public class SingleThreadDownloader {
    private SingleDownloadThread singleDownloadThread;
    /**
     * 下载的百分比
     */
    private long downloadPercent = 0;
    /**
     * 下载的 平均速度
     */
    private long downloadSpeed = 0;
    /**
     * 下载用的时间
     */
    private int usedTime = 0;
    /**
     * 当前时间
     */
    private long curTime;
    /**
     * 已下载长度
     */
    private long downloadSize;

    /**
     * 是否下载完成
     */
    private boolean isDownFinish = false;

    /**
     * 是否下载失败
     */
    private boolean isError = false;

    /**
     * 是否停止下载
     */
    private boolean isCancel = false;

    /**
     * 是否暂停下载
     */
    private boolean isPause = false;

    public void download(Context context, String url, String path,
                         long apkSize, DownloadProgressListener listener) {
        try {
            // 开始时间，放在循环外，求解的usedTime就是总时间
            long startTime = System.currentTimeMillis();
            isDownFinish = false;// 下载未完成
            singleDownloadThread = new SingleDownloadThread(context,
                    url,
                    path,
                    listener);
            singleDownloadThread.setPriority(7);
            singleDownloadThread.start();
            while (!isDownFinish) {// 循环判断所有线程是否完成下载
                Thread.sleep(50);
                isDownFinish = false;// 假定全部线程下载完成
                isError = false;
                //如果下载线程为空 或者 下载线程标识为下载完成但是下载进度却是-1，重新下载
                if (singleDownloadThread == null
                        || (singleDownloadThread.isFinish() && singleDownloadThread.getDownSize() == -1)) {
                    isDownFinish = false;// 设置标志为下载没有完成
                    isError = true;
//                    Log.d("SingleThreadDownloader", "下载失败，重新开启一条线程");


                    // 如果下载失败,再重新下载
                    singleDownloadThread = new SingleDownloadThread(
                            context,
                            url,
                            path,
                            listener);
                    singleDownloadThread.setPriority(7);
                    singleDownloadThread.start();
                }

                if(singleDownloadThread!=null && singleDownloadThread.isFinish())
                {
                    isDownFinish = true;
                }
                countDownProgress(listener,startTime,apkSize);
            }

            if (listener != null && isCancel) {
                // 如果结束下载的时候，下载百分比小于100，认为是取消下载
                listener.onDownCancel("");
                return;
            }
            if (listener != null) {
                countDownProgress(listener,startTime,apkSize);
                listener.onDownloadFinish("", path);
            }
        } catch (Exception e) {
            if (listener != null) {
                listener.onDownFailed("", e.getMessage());
            }
        }

    }

    private void countDownProgress(DownloadProgressListener listener,long startTime,long apkSize)
    {
        this.downloadSize = singleDownloadThread.getDownSize();
        downloadPercent = new Long((this.downloadSize * 100) / apkSize).intValue();
        curTime = System.currentTimeMillis();
        usedTime = (int) ((curTime - startTime) / 1000);
        System.out.println("startTime = " + startTime + ",curTime = " + curTime
                + " ,downloadSize = " + downloadSize + " ,usedTime ="
                + usedTime);

        if (usedTime == 0)
            usedTime = 1;
        downloadSpeed = (downloadSize / usedTime) / 1024;
        if (listener != null) {
            listener.onDownloading("", downloadSize, downloadSpeed,
                    downloadPercent);// 通知目前已经下载完成的数据长度
        }
    }
    public void cancelAllDownload() {
        // 如果cancelAll接口被调用，停止下载
        if (singleDownloadThread != null) {
            singleDownloadThread.cancelDown();
        }
        isDownFinish = true;
    }
}
