package com.tiangua.download.network.muldown;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.database.SQLException;
import android.os.AsyncTask;

import com.tiangua.download.callback.DownloadProgressListener;
import com.tiangua.download.db.DownProgress_Schema;
import com.tiangua.download.db.DownRecord_Schema;
import com.tiangua.download.service.ApkDownService;
import com.tiangua.download.util.DeviceUtil;
import com.tiangua.download.util.FileSizeUtil;
import com.tiangua.fast.db.DbFastControl;
import com.yagang.load.R;

/**
 * 文件下载器
 *
 * @author Administrator
 */
public class MulThreadDownloader extends AsyncTask<Void, Long, Void> implements
        Comparable<MulThreadDownloader> {

    private static final String TAG = "MulThreadDownloader";
    private Object obj = -1;
    //重试的最大次数
    private static final int MAX_RETRY_TIMES = 15;
    private int retryTimes = 0;

    private Context context;

    /* 要下载apk schema */
    private DownRecord_Schema download_record;

    /**
     * 下载监听
     */
    private DownloadProgressListener listener;
    /* 已下载文件长度 */
    private volatile long downloadSize = 0l;

    /* 原始文件长度 */
    private long fileSize = 0;

    /* 线程数 */
    private DownloadThread[] threads;

    /**
     * 本地保存文件
     */
    private File saveFile;

    /* 本地保存文件目录 */
    private String savePath;

    /**
     * 保存文件名
     */
    private String saveName;

    /* 缓存各线程下载的长度 */
    private Map<Integer, Long> data = new ConcurrentHashMap<Integer, Long>();

    /* 每条线程下载的长度 */
    private long block;

    /* 如果某个apk的数据不能被整除，会有额外长度 */
    private long blockAppend;

    /* 下载路径 */
    private String downloadUrl;
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
     * 是否是一个没有下载过的任务
     */
    private boolean isNewTask = true;

    /**
     * 是否下载完成
     */
    private boolean isFinish = true;

    /**
     * 是否取消下载
     */
    private boolean isCancel = false;

    /**
     * 是否暂停
     */
    private boolean isPause = false;

    /**
     * 是否下载异常
     */
    private boolean isDownError = false;

    /**
     * 下载异常
     */
    private Exception downExcption = null;

    /**
     * @param savePath      文件保存路径
     * @param pkgName       游戏包名
     * @param record_Schema apk详细信息
     * @param listener      下载监听
     */
    public MulThreadDownloader(Context context, String savePath,
                               String pkgName, DownRecord_Schema record_Schema,
                               DownloadProgressListener listener) {
        try {
            this.download_record = record_Schema;
            this.listener = listener;
            this.savePath = savePath;
            this.saveName = pkgName;
            this.context = context;
            this.downloadUrl = record_Schema.getApk_url();
            this.fileSize = record_Schema.getApkSize();
            this.threads = new DownloadThread[ApkDownService.MAX_THREAD_NUM];
        } catch (Exception e) {
            print(e.toString());
        }
    }

    /**
     * 获取此下载任务的唯一名称
     *
     * @return
     */
    public String getTaskName() {
        return this.saveName;
    }

    /**
     * 返回下载监听
     *
     * @return
     */
    public DownloadProgressListener getDownListener() {
        return listener;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        try {
            File rootFile = new File(savePath);
            if (!rootFile.exists() || !rootFile.isDirectory()) {
                rootFile.mkdirs();
            }
            if (listener != null) {
                isFinish = false;
                listener.onDownStart(download_record.getApk_pkg());

//                Log.d(TAG, "开始下载:" + download_record.getApk_pkg());
                // List<Object> list = new ArrayList<Object>();
                // list.add(download_record);


                List<Object> list = DbFastControl.getDbFast().query(
                        DownRecord_Schema.class, "apk_pkg=?", new String[]{download_record.getApk_pkg()});

                if (list != null && list.size() > 0) {
                    download_record = (DownRecord_Schema) list.get(0);
                    download_record.setDownState(DownRecord_Schema.APK_STATE_DOWNLOADING);
                    DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                            new String[]{download_record.getApk_pkg()});
                } else {
                    DbFastControl.getDbFast().insert(download_record);
                }
//                GamesHub.getHub().setDownloadApk(download_record);
            }
        } catch (Exception e) {
            e.printStackTrace();
            listener.onDownFailed(download_record.getApk_pkg(), e.getMessage());
        }
    }

    @Override
    protected void onProgressUpdate(Long... values) {
        super.onProgressUpdate(values);
        try {
            if (listener != null)
                // 通知目前已经下载完成的数据长度
                if (!isPause) {
                    if (download_record != null && download_record.getApk_pkg() != null) {
//                        Log.d(TAG, "onProgressUpdate pkg :" + download_record.getApk_pkg());
                        listener.onDownloading(download_record.getApk_pkg(), values[0], values[1], values[2]);
                    }
                }
        } catch (Exception e) {
            e.printStackTrace();
            isNewTask = false;
            isDownError = true;
            clearDownCache(this);
            // 更改此apk在数据库中下载状态为下载失败
            download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_ERROR);
            DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                    new String[]{download_record.getApk_pkg()});
//                HomeActivity.apkDownService.removeSelfFromCurrentQueue(this);
            String error = e.getMessage();
            downExcption = e;
            if (listener != null) {
                listener.onDownFailed(download_record.getApk_pkg(), error);
            }
//            Log.d(TAG, "下载失败:" + download_record.getApk_pkg() + " error:" + e.toString(), e);
        }
    }

    @Override
    protected Void doInBackground(Void... params) {
        try {
            if (initDownInfo()) {
                download();
            } else {
                isDownError = true;
                isFinish = true;
                if(downExcption != null)
                {
                	listener.onDownFailed(download_record.getApk_pkg(), "下载失败:"+downExcption.getMessage());
                }
                else {
                	listener.onDownFailed(download_record.getApk_pkg(),"下载失败，请查看错误日志");
				}
                
//                HomeActivity.apkDownService.removeSelfFromCurrentQueue(this);
            }
        } catch (Exception e) {
//            HomeActivity.apkDownService.removeSelfFromCurrentQueue(this);
            e.printStackTrace();
            String error = e.getMessage();
            listener.onDownFailed(download_record.getApk_pkg(), error);
        }
        return null;
    }

    @Override
    protected void onPostExecute(Void result) {
        super.onPostExecute(result);
        try {
            clearDownCache(this);
            if (!isCancel && !isDownError) {
                // 更改此apk在数据库中下载状态为下载完成
//                Log.d(TAG, "down is finish downState:" + DownRecord_Schema.APK_STATE_DOWNLOADED);
                download_record.setDownState(DownRecord_Schema.APK_STATE_DOWNLOADED);
                DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                        new String[]{download_record.getApk_pkg()});
                //将下载apk添加进gameHub的已下载map里
//                GamesHub.getHub().setDownloadApk(download_record);
                if (listener != null) {
                    listener.onDownloadFinish(download_record.getApk_pkg(), savePath);
                }
                return;
            }

            if (isCancel) {
                //删除下载进度记录
//                Log.d(TAG, "cancel down, delete down record and data");
                download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_CANCEL);
                DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                        new String[]{download_record.getApk_pkg()});
                clearDownCache(this);
                if (listener != null)
                    listener.onDownCancel(download_record.getApk_pkg());
                return;
            }

            if (isDownError) {
//                Log.d(TAG, "down error, delete down record and data");
                download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_ERROR);
                DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                        new String[]{download_record.getApk_pkg()});
                clearDownCache(this);
                if (listener != null)
                    listener.onDownFailed(download_record.getApk_pkg(), downExcption.getMessage());
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCancelled() {
        cancelAll();
        super.onCancelled();
    }

    private boolean initDownInfo() {
        boolean initSuccess = false;
        try {
//            URL url = new URL(downloadUrl);
//            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
//            conn.setConnectTimeout(10 * 1000);
//            conn.setRequestMethod("GET");
//            conn.setRequestProperty(
//                    "Accept",
//                    "image/gif, image/jpeg, image/pjpeg, image/pjpeg, application/x-shockwave-flash, application/xaml+xml, application/vnd.ms-xpsdocument, application/x-ms-xbap, application/x-ms-application, application/vnd.ms-excel, application/vnd.ms-powerpoint, application/msword, */*");
//            conn.setRequestProperty("Accept-Language", "zh-CN");
//            conn.setRequestProperty("Referer", downloadUrl);
//            conn.setRequestProperty("Charset", "UTF-8");
//            conn.setRequestProperty(
//                    "User-Agent",
//                    "Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 5.2; Trident/4.0; .NET CLR 1.1.4322; .NET CLR 2.0.50727; .NET CLR 3.0.04506.30; .NET CLR 3.0.4506.2152; .NET CLR 3.5.30729)");
//            conn.setRequestProperty("Connection", "Keep-Alive");
//            conn.connect();
//            printResponseHeader(conn);

//            this.fileSize = conn.getContentLength();// 根据响应获取文件大小
            if (this.fileSize <= 0)
                throw new RuntimeException("Unkown file size ");

            if (!DeviceUtil.existSDCard()) {
                // 更改此apk在数据库中下载状态为下载失败
                download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_ERROR);
                DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                        new String[]{download_record.getApk_pkg()});
                // 回调失败原因
                listener.onDownFailed(download_record.getApk_pkg(), "sd卡没有挂载");
//                Log.d(TAG, "下载失败:" + download_record.getApk_pkg() + " hasn't sdcard");
                return false;
            }
            //判断sd卡剩余空间是否足够下载此apk
            long sdAvailaleSize = DeviceUtil.getAvailaleSize();
            if (sdAvailaleSize < fileSize) {
                // 更改此apk在数据库中下载状态为下载失败
                download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_ERROR);
                DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                        new String[]{download_record.getApk_pkg()});
                // 回调失败原因
                listener.onDownFailed(download_record.getApk_pkg(),"sd卡剩余空间不足");
//                Log.d(TAG, "下载失败:" + download_record.getApk_pkg() + " fileSize > sdCardSize");
                return false;
            }

            saveFile = new File(savePath, saveName + ".apk");

            // 获取下载记录
            List<DownProgress_Schema> list_downlog = getDownloadSize(download_record.getApk_pkg());
            if (list_downlog != null && list_downlog.size() > 0) {
                // 如果存在下载记录,把各条线程已经下载的数据长度放入data中
                long apkDownedSize = 0l;
                for (DownProgress_Schema downProgress_Schema : list_downlog) {
                    data.put(downProgress_Schema.getDown_thread_id(),
                            downProgress_Schema.getDown_length());
                    apkDownedSize += downProgress_Schema.getDown_length();
                }

                // 判断本地是否还存在之前未下载完成的apk
                if (saveFile.exists() && saveFile.isFile()) {
                    // 判断此apk大小是否等于数据库中记录的下载大小
                    // 如果两者一样，选择重新下载，如果大小不一样，删除本地已下载文件，和数据库记录，重新下载
                    long saveFileSize = Math.round(FileSizeUtil.getFileOrFilesSize(
                            saveFile.getAbsolutePath(), FileSizeUtil.SIZETYPE_B));
                    if (saveFileSize != apkDownedSize) {
                        this.saveFile.delete();
                        this.saveFile.createNewFile();
                    } else {
                        if (data != null && data.size() == threads.length) {// 下面计算所有线程已经下载的数据长度
                            for (int i = 0; i < this.threads.length; i++) {
                                if (threads[i] != null) {
                                    this.downloadSize += this.data.get(threads[i]);
                                    print("已经下载的长度" + this.downloadSize);
                                } else {
                                    this.saveFile.delete();
                                    this.saveFile.createNewFile();
                                    data.clear();
                                    apkDownedSize += 0l;
                                    clearDownRecord(saveName);
                                }
                            }
                        }
                        else
                        {
                            data.clear();
                            apkDownedSize += 0l;
                            clearDownRecord(saveName);
                        }
                    }
                } else {
                    //如果存在下载记录，但是apk又不存在，清空下载记录
                    data.clear();
                    apkDownedSize += 0l;
                    clearDownRecord(saveName);
                }
            } else {
                if (saveFile.exists())
                    this.saveFile.delete();
                this.saveFile.createNewFile();
            }

            // 计算每条线程下载的数据长度
            // 如果apk的大小可以被下载线程数整除，直接获取每条线程的下载长度
            if (this.fileSize % this.threads.length == 0) {
                this.block = this.fileSize / this.threads.length;
            } else {
                // 如果apk大小不能被下载线程数整除，取余，并追加到最后一条线程中
                this.blockAppend = this.fileSize % this.threads.length;
                this.block = (this.fileSize - this.blockAppend)
                        / this.threads.length;
            }
            initSuccess = true;
        } catch (Exception e) {
            // 更改此apk在数据库中下载状态为下载失败
            isDownError = true;
            isFinish = true;
            download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_ERROR);
            DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                    new String[]{download_record.getApk_pkg()});
            String error = e.getMessage();
            downExcption = e;
            listener.onDownFailed(download_record.getApk_pkg(), error);
//            Log.d(TAG, "下载失败:" + download_record.getApk_pkg() + " error:" + e.toString(), e);
//            HomeActivity.apkDownService.removeSelfFromCurrentQueue(this);
            e.printStackTrace();
        }
        return initSuccess;
    }

    /**
     * 开始下载文件
     *
     * @return 已下载文件大小
     * @throws Exception
     */
    public void download() throws Exception {
        try {
            isNewTask = false;
            RandomAccessFile randOut = new RandomAccessFile(this.saveFile, "rw");
            if (this.fileSize > 0)
                randOut.setLength(this.fileSize);
            randOut.close();

            if (this.data.size() != this.threads.length) {
                this.data.clear();

                for (int i = 0; i < this.threads.length; i++) {
                    this.data.put(i, 0l);// 初始化每条线程已经下载的数据长度为0
                }
            }
            // 初始化下载线程数组
            initDownThreads();
            // 开始时间，放在循环外，求解的usedTime就是总时间
            long startTime = System.currentTimeMillis();
            isFinish = false;
            while (!isFinish) {

                //如果isPause == true 暂停下载调度线程
                if (isPause) {
                    synchronized (obj) {
                        //修改数据库中下载住状态为暂停
                        download_record.setApkDownSize(this.downloadSize);
                        download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_PAUSE);
                        DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                                new String[]{download_record.getApk_pkg()});
                        //时刻刷新正在下载apk缓存
//                        GamesHub.getHub().setDownloadApk(download_record);
                        listener.onDownPause(download_record.getApk_pkg());
                        obj.wait();
                    }
//                    Log.d(TAG, "redown and update db");
                    //重新开始下载时，修改下载状态为下载中
                    download_record.setDownState(DownRecord_Schema.APK_STATE_DOWNLOADING);
                    DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                            new String[]{download_record.getApk_pkg()});
                    //时刻刷新正在下载apk缓存
//                    GamesHub.getHub().setDownloadApk(download_record);
                }

                // 假定全部线程下载完成
                isFinish = true;

                // 这里可以好好考虑写一个方法，判断某条线程有没有在下载数据
                for (int i = 0; i < this.threads.length; i++) {
                    DownloadThread downloadThread = this.threads[i];
                    if (downloadThread != null) {
                        if (!downloadThread.isFinish()) {
                            isFinish = false;
                            this.data.put(i, downloadThread.getDownLength());
                        }

                        //如果第i条下载线程
                        // 1.下载状态为完成，但是下载长度为-1,说明它是有异常，那么重启一条线程重新下载
                        // 2.如果isDwownError 为 true 重新开启一条下载线程
                        if ((retryTimes < MAX_RETRY_TIMES && (downloadThread.isFinish() && downloadThread.getDownLength() <= 0l) || downloadThread.isDownError())) {
//                            Log.d(TAG, "retryTimes:" + retryTimes);
                            isDownError = true;
                            downExcption = downloadThread.getDownException();
                            isFinish = false;
                            createNewDownThead(i);
                        } else {
                            //如果第i条下载线程不为下载异常状态,那么检测这条线程的下载速度
                            if (!downloadThread.isDownError()) {
                                downloadThread.setDownTimes(downloadThread.getDownTimes() + 1);
                                checkThreadDownSpeed(i);
                            } else {
                                downExcption = downloadThread.getDownException();
                            }
                        }
                    } else {
                        createNewDownThead(i);
                    }

                    // 循环判断所有线程是否完成下载
                    Thread.sleep(100);
                }
                countDownInfo(startTime);
                onProgressUpdate(downloadSize, downloadSpeed, downloadPercent);
                //将下载进度随时更新到数据库
                download_record.setApkDownSize(this.downloadSize);
                download_record.setDownState(DownRecord_Schema.APK_STATE_DOWNLOADING);
                DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                        new String[]{download_record.getApk_pkg()});
                //时刻刷新正在下载apk缓存
//                GamesHub.getHub().setDownloadApk(download_record);

                if (isCancel)
                    isFinish = true;

                if (isDownError)
                    isFinish = true;

            }
            isFinish = true;
            countDownInfo(startTime);
            onProgressUpdate(downloadSize, downloadSpeed, downloadPercent);
            onPostExecute(null);
        } catch (Exception e) {
            e.printStackTrace();
            isNewTask = false;
            isDownError = true;
            clearDownCache(this);
            // 更改此apk在数据库中下载状态为下载失败
            download_record.setDownState(DownRecord_Schema.APK_STATE_DOWN_ERROR);
            DbFastControl.getDbFast().update(download_record, "apk_pkg=?",
                    new String[]{download_record.getApk_pkg()});
//                HomeActivity.apkDownService.removeSelfFromCurrentQueue(this);
            String error = e.getMessage();
            downExcption = e;
            if (listener != null) {
                listener.onDownFailed(download_record.getApk_pkg(), error);
            }
//            Log.d(TAG, "下载失败:" + download_record.getApk_pkg() + " error:" + e.toString(), e);
        }
    }


    /**
     * 重新下载
     */
    public void reDown() {
        try {
//            Log.d(TAG, "redown");
            synchronized (obj) {
                obj.notifyAll();
                isPause = false;
                for (int i = 0; i < this.threads.length; i++) {
                    DownloadThread downloadThread = this.threads[i];
                    downloadThread.reDown();
//                    Log.d(TAG, "reDown down thread-" + i);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void pauseDown() {
//        Log.d(TAG, "pause down");
        isPause = true;
    }

    /**
     * 取消下载
     */
    public void cancelAll() {
//        Log.d(TAG, "cancel down");
        isCancel = true;
    }


    /**
     * 返回是否是取消下载
     *
     * @return
     */
    public boolean isCancel() {
        return isCancel;
    }

    /**
     * 是否下载完成
     *
     * @return
     */
    public boolean isFinish() {
        return isFinish;
    }

    /**
     * 是否是一条新的下载线程
     *
     * @return true:运行中 false：没有运行
     */
    public boolean isNewThread() {
        return isNewTask;
    }

    public void setNewThread(boolean isNewThread) {
        this.isNewTask = isNewThread;
    }

    /**
     * 是否暂停
     *
     * @return
     */
    public boolean isPause() {
        return isPause;
    }

    /**
     * 检测i线程下载速度是否合格，不合格就重新开启一条线程下载
     *
     * @param i
     */
    private void checkThreadDownSpeed(int i) {
        DownloadThread downloadThread = this.threads[i];
        if (downloadThread == null) {
            createNewDownThead(i);
        } else {
            int downTimes = downloadThread.getDownTimes();
            //每隔一段时间，检测一下i线程下载速度
            if (downTimes == 0 || downTimes % ApkDownService.CHECK_DOWN_SPEED_TIME != 0) {
                return;
            }
            //获取上一时间段下载了多少数据
            long lastTimeDownPosition = downloadThread.getLastTimeDownPosition();
            //然后将当前上一时间段下载大小设置为当前总下载大小
            downloadThread.setLastTimeDownPosition(downloadThread.getCurPosition());

//            long downSize = curPosition - startPosition;
//            Log.d(TAG,"downTimes:"+downTimes +" startPosition:"+startPosition +" curPosition:"+curPosition);
//            Log.d(TAG, "downTimes:" + downTimes + " downSize:" + lastTimeDownPosition + " MinDownSize:" + ApkDownService.MIN_DOWN_DATA_SIZE);
            // 如果这一段时间内，下载的数据小于规定大小，取消这条线程的下载，重新开一条线程
            if (lastTimeDownPosition < ApkDownService.MIN_DOWN_DATA_SIZE) {
//                Log.d(TAG, "this thread downSpeed is slow,create a new thread");
                //关闭这条线程
                threads[i].setFinish(true);
                createNewDownThead(i);
            }
        }
    }

//    private void createNewThread(int i) {
//
//        //获取此线程之前下载的数据长度
//        long downLength = data.get(i);
//        // 将此前下载的数据长度累加到新的开始位置，这样就不用重复下载之前已经下载过的进度
//        long startPosition = block * i + downLength;
//        long threadBlock = block;
//        long endPosition = block * i + block - 1;
//        //计算是否每条线程的下载长度是一样的
//        if (i == (threads.length - 1) && blockAppend > 0l) {
//            threadBlock = block + blockAppend;
//            endPosition = fileSize;
//        } else if (i == (threads.length - 1) && blockAppend <= 0l) {
//            endPosition = fileSize;
//        }
//
//        Log.d(TAG, "this file size:" + fileSize);
//        Log.d(TAG, "last thread has downed size:" + downLength);
//        Log.d(TAG, "new thread should down size from:" + startPosition + " to:" + endPosition);
//
//        DownProgress_Schema schema = new DownProgress_Schema();
//        schema.setApk_name(download_record.getApk_name());
//        schema.setApk_url(download_record.getApk_url());
//        schema.setDown_length(downLength);
//        schema.setDown_thread_id(i);
//        schema.setPkg_name(download_record.getApk_pkg());
//        schema.setDown_block(threadBlock);
//
//        this.threads[i] = new DownloadThread(this, schema, saveFile,
//                startPosition, endPosition, listener);
//        this.threads[i].setDownTimes(0);
//        this.threads[i].setPriority(7);
//        this.threads[i].start();
//    }

    /**
     * 重新创建一个下载线程 放进 线程数组下表i里
     *
     * @param i
     */
    private void createNewDownThead(int i) {

        if (retryTimes >= MAX_RETRY_TIMES) {
            isFinish = true;
            isDownError = true;
            return;
        }

        //获取此线程之前下载的数据长度
        long downLength = data.get(i);
        // 将此前下载的数据长度累加到新的开始位置，这样就不用重复下载之前已经下载过的进度
        long startPosition = block * i + downLength;
        long threadBlock = block;
        long endPosition = block * i + block - 1;
        //计算是否每条线程的下载长度是一样的
        if (i == (threads.length - 1) && blockAppend > 0l) {
            threadBlock = block + blockAppend;
            endPosition = fileSize;
        } else if (i == (threads.length - 1) && blockAppend <= 0l) {
            endPosition = fileSize;
        }

        //判断线程是否为空 或者 线程是否下载完毕并且下载长度小于0 或者 是否下载失败
        //并将旧线程下载完成状态设置为true
        DownloadThread downloadThread = this.threads[i];
        if (downloadThread != null && ((downloadThread.isFinish() && downloadThread.getDownLength() <= 0) || downloadThread.isDownError())) {// 如果下载失败,再重新下载
            downloadThread.setFinish(true);
        }

//        Log.d(TAG, "this file size:" + fileSize);
//        Log.d(TAG, "last thread has downed size:" + downLength);
//        Log.d(TAG, "new thread should down size from:" + startPosition + " to:" + endPosition);

        DownProgress_Schema schema = new DownProgress_Schema();
        schema.setApk_name(download_record.getApk_name());
        schema.setApk_url(download_record.getApk_url());
        schema.setDown_length(downLength);
        schema.setDown_thread_id(i);
        schema.setPkg_name(download_record.getApk_pkg());
        schema.setDown_block(threadBlock);

        this.threads[i] = new DownloadThread(this, schema, saveFile,
                startPosition, endPosition, listener);
        this.threads[i].setDownTimes(0);
        this.threads[i].setPriority(7);
        this.threads[i].start();
        retryTimes++;
    }

    /**
     * 初始化下载线程，并保存到数据库
     */
    private void initDownThreads() {
        // 开启线程进行下载
        List<DownProgress_Schema> list = new ArrayList<DownProgress_Schema>();
        for (int i = 0; i < this.threads.length; i++) {
            DownProgress_Schema schema = new DownProgress_Schema();
            long downLength = data.get(i);
            long threadBlock = block;
            long startPosition = block * i + downLength;
            long endPosition = block * i + block - 1;
            if (i == (threads.length - 1) && blockAppend > 0l) {
                threadBlock = block + blockAppend;
                endPosition = fileSize;
            } else if (i == (threads.length - 1) && blockAppend <= 0l) {
                endPosition = fileSize;
            }
//            Log.d(TAG, "this file size:" + fileSize);
//            Log.d(TAG, "last thread has downed size:" + downLength);
//            Log.d(TAG, "new thread should down size from:" + startPosition + " to:" + endPosition);

            schema.setApk_name(download_record.getApk_name());
            schema.setApk_url(download_record.getApk_url());
            schema.setDown_length(downLength);
            schema.setDown_thread_id(i);
            schema.setPkg_name(download_record.getApk_pkg());
            schema.setDown_block(threadBlock);
            if (downLength < threadBlock && this.downloadSize < this.fileSize) {// 判断线程是否已经完成下载,否则继续下载
                this.threads[i] = new DownloadThread(this, schema, saveFile,
                        startPosition, endPosition, listener);
                this.threads[i].setDownTimes(0);
                this.threads[i].setPriority(7);
                this.threads[i].start();
            } else {
                this.threads[i] = null;
            }
            list.add(schema);
        }

       saveDownloadRecord(list);
    }

    /**
     * 计算下载相关数据
     *
     * @param startTime
     */
    private void countDownInfo(long startTime) {
        downloadPercent = new Long((this.downloadSize * 100) / fileSize).intValue();
        curTime = System.currentTimeMillis();
        usedTime = (int) ((curTime - startTime) / 1000);
        System.out.println("startTime = " + startTime + ",curTime = " + curTime
                + " ,downloadSize = " + downloadSize + " ,usedTime ="
                + usedTime);

        if (usedTime == 0)
            usedTime = 1;
        downloadSpeed = (downloadSize / usedTime) / 1024;
    }

    /**
     * 清空相关缓存
     */
    private void clearDownCache(MulThreadDownloader downloader) {
        block = 0;
        curTime = 0;
        usedTime = 0;
        downloadPercent = 0;
        downloadSize = 0;
        downloadSpeed = 0;
        fileSize = 0;

        if (data != null) {
            data.clear();
        }

//        HomeActivity.apkDownService.removeSelfFromCurrentQueue(downloader);
    }

    /**
     * 获取线程数
     */
    public int getThreadSize() {
        return threads.length;
    }

    /**
     * 获取文件大小
     *
     * @return
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * 累计已下载大小
     *
     * @param size
     */
    protected void append(int size) {
        downloadSize += size;
    }

    /**
     * 更新指定线程最后下载的位置
     */
    protected synchronized void update(DownProgress_Schema progress_Schema) {
        this.data.put(progress_Schema.getDown_thread_id(),
                progress_Schema.getDown_length());
        updateDownSpeed(progress_Schema);
    }


	/**
	 * 获取文件名
	 * 
	 * @param conn
	 * @return
	 */
	private String getFileName(HttpURLConnection conn) {
		String filename = this.downloadUrl.substring(this.downloadUrl
				.lastIndexOf('/') + 1);

		if (filename == null || "".equals(filename.trim())) {// 如果获取不到文件名称
			for (int i = 0;; i++) {
				String mine = conn.getHeaderField(i);

				if (mine == null)
					break;

				if ("content-disposition".equals(conn.getHeaderFieldKey(i)
						.toLowerCase())) {
					Matcher m = Pattern.compile(".*filename=(.*)").matcher(
							mine.toLowerCase());
					if (m.find())
						return m.group(1);
				}
			}

			filename = UUID.randomUUID() + ".tmp";// 默认取一个文件名
		}

		return filename;
	}

	/**
	 * 获取Http响应头字段
	 * 
	 * @param http
	 * @return
	 */
	public static Map<String, String> getHttpResponseHeader(
			HttpURLConnection http) {
		Map<String, String> header = new LinkedHashMap<String, String>();

		for (int i = 0;; i++) {
			String mine = http.getHeaderField(i);
			if (mine == null)
				break;
			header.put(http.getHeaderFieldKey(i), mine);
		}

		return header;
	}

	/**
	 * 保存每条线程已经下载的文件长度
	 * 
	 * @param path
	 * @param map
	 */
	private void saveDownloadRecord(List<DownProgress_Schema> progressList) {
		try {
			List<Object> list = new ArrayList<Object>();
			// Collection<DownProgress_Schema> downList = map.values();
			for (DownProgress_Schema downProgress_Schema : progressList) {
				list.add(downProgress_Schema);
			}
			long insertCount = DbFastControl.getDbFast().insertList(list);
			// Log.d("DownloadService", "insert downprogress count:" +
			// insertCount);
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 获取每条线程已经下载的文件长度
	 * 
	 * @param path
	 * @return
	 */
	private List<DownProgress_Schema> getDownloadSize(String pkgName) {
		List<DownProgress_Schema> downProgressList = null;
		try {
			downProgressList = new ArrayList<DownProgress_Schema>();
			List<Object> list = DbFastControl.getDbFast().query(
					DownProgress_Schema.class, "apk_pkg=?",
					new String[] { pkgName });

			if (list != null && list.size() > 0) {
				for (Object object : list) {
					DownProgress_Schema schema = (DownProgress_Schema) object;
					downProgressList.add(schema);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return downProgressList;
	}
	

    /**
     * 实时更新每条线程已经下载的文件长度
     * 
     * @param path
     * @param map
     */
    public void updateDownSpeed(DownProgress_Schema obj)
    {
        try
        {
            DbFastControl.getDbFast().update(
                    obj,
                    "apk_pkg=? and down_thread_id=?",
                    new String[] { obj.getPkg_name(),
                            "" + obj.getDown_thread_id() });
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
    }

	/**
	 * 清空指定apk的下载记录
	 * 
	 * @param apk包名
	 */
	private void clearDownRecord(String pkgName) {
		try {
			DbFastControl.getDbFast().delete(DownProgress_Schema.class,
					"apk_pkg=?", new String[] { pkgName });
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * 打印Http头字段
	 * 
	 * @param http
	 */
	private static void printResponseHeader(HttpURLConnection http) {
		Map<String, String> header = getHttpResponseHeader(http);

		for (Map.Entry<String, String> entry : header.entrySet()) {
			String key = entry.getKey() != null ? entry.getKey() + ":" : "";
			print(key + entry.getValue());
		}
	}

	private static void print(String msg) {
		// Log.i(TAG, msg);
	}

	@Override
	public int compareTo(MulThreadDownloader another) {
		// TODO Auto-generated method stub
		return 0;
	}
}
