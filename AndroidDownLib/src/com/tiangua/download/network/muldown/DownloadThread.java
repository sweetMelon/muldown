package com.tiangua.download.network.muldown;

import com.tiangua.download.callback.DownloadProgressListener;
import com.tiangua.download.db.DownProgress_Schema;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 下载线程
 *
 * @author Administrator
 */
public class DownloadThread extends Thread {

    private Object localObj = new Object();

    private static final String TAG = "DownloadThread";
    /**
     * 缓冲区
     */
    private static final int BUFF_SIZE = 1024 * 20;
    /**
     * 需要下载的apk的schema
     */
    private DownProgress_Schema progress_Schema;
    /**
     * 需要下载的URL
     */
    private String downUrl;
    /**
     * 缓存的FIle
     */
    private File saveFile;
    /**
     * 开始位置
     */
    private long startPosition;
    /**
     * 结束位置
     */
    private long endPosition;
    /**
     * 当前下载位置
     */
    private long curPosition;
    /**
     * 上一个时间段下载的数据
     */
    private long lastTimeDownPosition;

    /**
     * 已下载多长时间
     */
    private int downTimes;
    /**
     * 完成
     */
    private boolean finished = false;
    /**
     * 是否下载异常
     */
    private boolean downError = false;

    /**
     * 下载异常
     */
    private Exception downExcption = null;

    /**
     * 已经下载数据大小
     */
    private long downloadSize = 0l;
    /**
     * 每条线程要下载的大小 *
     */
    private long block = 0l;

    /* 下载开始位置 */
    private int threadId = -1;
    private MulThreadDownloader downloader;
    private DownloadProgressListener listener;

    public DownloadThread(MulThreadDownloader downloader,
                          DownProgress_Schema progress_Schema, File saveFile, long startPosition, long endPosition, DownloadProgressListener listener) {
        this.progress_Schema = progress_Schema;
        this.downUrl = progress_Schema.getApk_url();
        this.saveFile = saveFile;
        this.block = progress_Schema.getDown_block();
        this.downloader = downloader;
        this.threadId = progress_Schema.getDown_thread_id();
        this.downloadSize = progress_Schema.getDown_length();
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.listener = listener;
    }

    @Override
    public void run() {
        if (downloadSize < block) {// 未下载完成
            try {
                URL url = new URL(downUrl);
                HttpURLConnection http = (HttpURLConnection) url.openConnection();
                http.setConnectTimeout(20 * 1000);
                http.setRequestMethod("GET");
                http.setRequestProperty(
                        "Accept",
                        "image/gif, image/jpeg, image/pjpeg, image/pjpeg, application/x-shockwave-flash, application/xaml+xml, application/vnd.ms-xpsdocument, application/x-ms-xbap, application/x-ms-application, application/vnd.ms-excel, application/vnd.ms-powerpoint, application/msword, */*");
                http.setRequestProperty("Accept-Language", "zh-CN");
                http.setRequestProperty("Referer", downUrl.toString());
                http.setRequestProperty("Charset", "UTF-8");
                http.setRequestProperty("Range", "bytes=" + startPosition + "-"
                        + endPosition);// 设置获取实体数据的范围
                http.setRequestProperty(
                        "User-Agent",
                        "Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 5.2; Trident/4.0; .NET CLR 1.1.4322; .NET CLR 2.0.50727; .NET CLR 3.0.04506.30; .NET CLR 3.0.4506.2152; .NET CLR 3.5.30729)");
                http.setRequestProperty("Connection", "Keep-Alive");

                InputStream inStream = http.getInputStream();
                print("Thread " + this.threadId
                        + " start download from position " + startPosition + " end position " + endPosition);
                RandomAccessFile threadfile = new RandomAccessFile(
                        this.saveFile,
                        "rwd");
                threadfile.seek(startPosition);

                byte[] buf = new byte[BUFF_SIZE];
                BufferedInputStream bis = new BufferedInputStream(
                        http.getInputStream(),
                        BUFF_SIZE);
                curPosition = startPosition;
                // 当前位置小于结束位置 继续下载
                while ((curPosition < endPosition) && !finished && !downloader.isCancel()) {

                    if(downloader.isPause())
                    {
                        synchronized (localObj)
                        {
                            localObj.wait();
                        }
                    }

                    if(finished)
                        break;

                    int len = bis.read(buf, 0, BUFF_SIZE);
                    // 下载完成
                    if (len == -1) {
                        break;
                    }
                    curPosition = curPosition + len;
                    if (curPosition > endPosition) { // 如果下载多了，则减去多余部分
//                        System.out.println("  curPosition > endPosition  !!!!");
                        //下载的多余部分
                        long extraLen = curPosition - endPosition;
                        //用本次读取的长度len 减去 下载的多余部分extraLen +1
                        //得到当前应该下载的长度
                        downloadSize += (len - extraLen + 1);
                        len = new Long(len - extraLen + 1).intValue();
                    } else {
                        downloadSize += len;
                    }
                    //将下载多的数据写入本地文件
                    threadfile.write(buf, 0, len);
                    progress_Schema.setDown_thread_id(this.threadId);
                    progress_Schema.setDown_length(downloadSize);
                    downloader.append(len);
                }

                // 如果下载的数据等于需要下载的数据，认为是下载成功
                if(downloadSize == block)
                {
                    downloader.update(progress_Schema);
                    this.finished = true;
                }
                else if(downloadSize <= 0l || downloadSize > block) {
                    this.downError = true;
                    downloadSize = -1l;
                    downloader.update(progress_Schema);
//                    Log.d(TAG, "downUrl:" + downUrl + " is error = " + this.downError);
                }
                threadfile.close();
                inStream.close();
                print("Thread " + this.threadId + " download finish");

            } catch (Exception e) {
                this.finished = true;
                this.downloadSize = -1;
                this.downError = true;
                downExcption = e;
                downloader.update(progress_Schema);
                print("url:" + downUrl + "-Thread " + this.threadId + ":" + e);
            }
        }
    }

    private static void print(String msg) {
//        Log.i(TAG, msg);
    }

    public long getStartPosition()
    {
        return this.startPosition;
    }

    public long getCurPosition()
    {
        return this.curPosition;
    }

    public void setDownTimes(int downTimes)
    {
        this.downTimes = downTimes;
    }

    public int getDownTimes()
    {
        return downTimes;
    }

    /**
     * 传入上一时间段所下载的数据
     * @param downPostion
     */
    public void setLastTimeDownPosition(long downPostion)
    {
        this.lastTimeDownPosition = downPostion;
    }

    /**
     * 返回上一时间段，所下载了多少数据
     * @return
     */
    public long getLastTimeDownPosition()
    {
        return curPosition - lastTimeDownPosition;
    }

    public void setFinish(boolean isFinish)
    {
        this.finished = isFinish;
    }
    /**
     * 下载是否完成
     *
     * @return
     */
    public boolean isFinish() {
        return finished;
    }

    /**
     * 是否下载异常
     *
     * @return true 下载异常  false 不是下载异常
     */
    public boolean isDownError() {
        return downError;
    }

    /**
     * 获取下载的异常信息
     * @return 异常对象
     */
    public Exception getDownException()
    {
        return downExcption;
    }

    /**
     * 已经下载的内容大小
     *
     * @return 如果返回值为-1,代表下载失败
     */
    public long getDownLength() {
        return downloadSize;
    }

    /**
     * 重新下载
     */
    public void reDown()
    {
        synchronized (localObj)
        {
            localObj.notifyAll();
        }
    }
}
