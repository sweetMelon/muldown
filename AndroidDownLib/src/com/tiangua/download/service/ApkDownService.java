package com.tiangua.download.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
//import android.util.Log;
import android.widget.Toast;

import com.tiangua.download.callback.DownloadProgressListener;
import com.tiangua.download.db.DownRecord_Schema;
import com.tiangua.download.network.muldown.MulThreadDownloader;
import com.tiangua.download.network.singledown.SingleThreadDownloader;
import com.tiangua.fast.db.DbFastControl;
//import com.ipay.wallet.Hubs.GamesHub;
//import com.ipay.wallet.network.pojos.schemas.Game_Schema;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Created by Administrator on 2015/6/18.
 */
public class ApkDownService extends Service {

    private static final String TAG = "ApkDownService";
    private Object obj = new Object();

    private Context context;

    //默认线程池
	private static Executor defaultExecutor = Executors.newCachedThreadPool();
    // 同一时间最多只能下载apk数量
    public static int MAX_APK_DOWN_NUM = 1;
    // 等待队列可以容纳的最大长度
    public static int MAX_WATTING_DOWN_NUM = 5;
    // 下载一个apk同时最多只能开启线程数
    public static int MAX_THREAD_NUM = 3;
    // 每隔多少秒，检测一次所有的线程在这段时间的下载速度
    public static int CHECK_DOWN_SPEED_TIME = 10;
    // 每多少秒至少要下载多少数据
    public static long MIN_DOWN_DATA_SIZE = 10 * 1024;

    //下载服务
    public static ApkDownService apkDownService = null;
    public static ServiceConnection conn = null;

    private SingleThreadDownloader singleDownLoader;
    //    // 获取所有的下载记录
//    private static Map<String, DownRecord_Schema> map;
    // 下载队列调度manager
    private DownManagerDispatch downManagerDispatch;
    //正在下载进度监听队列
    public static List<DownloadProgressListener> currentDownListeners = new ArrayList<DownloadProgressListener>();
    //    //等待下载进度监听队列
//    private Map<String, DownloadProgressListener> wattingDownListeners = new HashMap<String, DownloadProgressListener>();
    // 正在下载队列
    private BlockingQueue<MulThreadDownloader> currentDownloads = new ArrayBlockingQueue<MulThreadDownloader>(MAX_APK_DOWN_NUM, true);
    // 暂停下载队列
    private BlockingQueue<MulThreadDownloader> pauseDownloads = new ArrayBlockingQueue<MulThreadDownloader>(100, true);
    // 等待下载apk 队列
    private BlockingQueue<MulThreadDownloader> wattingDownloads = new ArrayBlockingQueue<MulThreadDownloader>(MAX_WATTING_DOWN_NUM, true);
    // 下载完毕apk 队列
    private BlockingQueue<MulThreadDownloader> finishDownloads = new LinkedBlockingQueue<MulThreadDownloader>();

    /**
     * 是否取消下载
     */
    private boolean isCancel = false;

    public class ApkDownServiceBinder extends Binder {
        public ApkDownService getApkDownService() {
            return ApkDownService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new ApkDownServiceBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        apkDownService = this;
        //log.d(TAG, "down service is run");
        downManagerDispatch = new DownManagerDispatch();
        downManagerDispatch.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        finish();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    private class DownManagerDispatch extends Thread {
        @Override
        public void run() {
            super.run();
            isCancel = false;
            while (!isCancel) {
                try {
                    //log.d(TAG, "check down thread");
                    if (currentDownloads != null && currentDownloads.size() > 0) {
                        //log.d(TAG, "have " + currentDownloads.size() + " task to down");

                        Iterator<MulThreadDownloader> it = currentDownloads.iterator();
                        while (it.hasNext()) {
                            MulThreadDownloader downloader = it.next();
                            if (downloader.isNewThread() || (downloader.isNewThread() && downloader.isPause())) {
                                //log.d(TAG, "get task from loadingQueue,start down");
                                downloader.executeOnExecutor(Executors.newCachedThreadPool());
                            } else if (downloader.isPause()) {
                                //log.d(TAG, "get task from loadingQueue, redown when task is pause");
                                downloader.reDown();
                            }
                        }
                    }
                    if (ApkDownService.this.currentDownloads != null && ApkDownService.this.currentDownloads.size() <= 0) {
                        synchronized (obj) {
                            //log.d(TAG, "wait for down task");
                            obj.wait();
                        }
                    }
                    Thread.sleep(2000L);
                } catch (Exception e) {
                    //log.d(TAG, e.getMessage());
                }
            }
        }
    }

    /**
     * 开始下载
     *
     * @param saveDir                  下载文件的本地存放地址
     * @param downloadProgressListener 下载监听器
     */
    public void startDown(String saveDir,
                          DownRecord_Schema record_Schema,
                          DownloadProgressListener downloadProgressListener) {
        MulThreadDownloader mulDownloader = null;
        try {
            mulDownloader = new MulThreadDownloader(ApkDownService.this, saveDir,
                    record_Schema.getApk_pkg(),
                    record_Schema,
                    new MyLoadListener(record_Schema.getApk_pkg()));
            checkTaskStatus(mulDownloader, downloadProgressListener);
        } catch (Exception e) {
            if (downloadProgressListener != null)
                downloadProgressListener.onDownFailed(record_Schema.getApk_pkg(),e.toString());
            e.printStackTrace();
            removeSelfFromCurrentQueue(mulDownloader.getTaskName());
        }
    }

    private class MyLoadListener implements DownloadProgressListener {

        String pkgName;

        public MyLoadListener(String pkg) {
            this.pkgName = pkg;
        }

        @Override
        public void onDownStart(String pkg) {
            try {
                if (currentDownListeners != null && currentDownListeners.size() > 0) {
                    int size = currentDownListeners.size();
                    for (int i = 0; i < size; i++) {
                        currentDownListeners.get(i).onDownStart(pkg);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                onDownFailed(pkg,e.getMessage());
            }
        }

        @Override
        public void onDownloading(String pkg, long allDownLoadedSize, long downloadSpeed,
                                  final long downLoadPrecent) {
            if (currentDownListeners != null && currentDownListeners.size() > 0) {
                int size = currentDownListeners.size();
                for (int i = 0; i < size; i++) {

                    if (currentDownListeners.get(i) != null)
                        //log.d(TAG,"onloading pkg: " + pkg);
                        currentDownListeners.get(i).onDownloading(pkg, allDownLoadedSize, downloadSpeed, downLoadPrecent);
                }
            }
        }

        @Override
        public void onDownloadFinish(String pkg,String saveFile) {
            try {
                if (currentDownListeners != null && currentDownListeners.size() > 0) {
                    int size = currentDownListeners.size();
                    for (int i = 0; i < size; i++) {

                        if (currentDownListeners.get(i) != null)
                            currentDownListeners.get(i).onDownloadFinish(pkg,saveFile);
                    }
                }
                removeSelfFromCurrentQueue(pkgName);
                installApk(getLocalApkPath(ApkDownService.this, pkgName));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDownFailed(String pkg,String error) {
            if (currentDownListeners != null && currentDownListeners.size() > 0) {
                int size = currentDownListeners.size();
                for (int i = 0; i < size; i++) {

                    if (currentDownListeners.get(i) != null)
                        currentDownListeners.get(i).onDownFailed(pkg,error);
                }
                removeSelfFromCurrentQueue(pkgName);
            }
        }

        @Override
        public void onDownCancel(String pkg) {
            if (currentDownListeners != null && currentDownListeners.size() > 0) {
                int size = currentDownListeners.size();
                for (int i = 0; i < size; i++) {

                    if (currentDownListeners.get(i) != null)
                        currentDownListeners.get(i).onDownCancel(pkg);
                }
                removeSelfFromCurrentQueue(pkgName);
            }
        }

        @Override
        public void onDownPause(String pkg) {
            if (currentDownListeners != null && currentDownListeners.size() > 0) {
                int size = currentDownListeners.size();
                for (int i = 0; i < size; i++) {

                    if (currentDownListeners.get(i) != null)
                        currentDownListeners.get(i).onDownCancel(pkg);
                }
                removeSelfFromCurrentQueue(pkgName);
            }
        }
    }

    /**
     * 在一个下载任务完成后，将它从正在下载队列取出，并从等待下载队列中取出一个任务开始执行下载
     *
     */
    public synchronized void removeSelfFromCurrentQueue(String taskName) {
        try {
//            GamesHub.getHub().setDownloadApkMap(getAllDownedApk());
            if (currentDownloads != null && currentDownloads.size() > 0) {
//                Iterator<MulThreadDownloader> itTask = currentDownloads.iterator();
//                MulThreadDownloader task = null;
//                while (itTask.hasNext()) {
//                    task = (MulThreadDownloader) itTask.next();
//                    String tName = task.getTaskName();
//                    if (taskName.equals(tName)) {
//                        break;
//                    }
//                    task = null;
//                }
                //log.d(TAG, "loadingQueue.size=" + currentDownloads.size());
                MulThreadDownloader task = currentDownloads.take();
                if (task != null) {
//                    boolean isRemove = false;
//                    isRemove = currentDownloads.remove(task);
//                    Log.d(TAG, "将自己从正在下载队列删除并添加进已下载队列 isRemove:" + isRemove);
                    //log.d(TAG, "take an loaded task from loadingQueue,the task name is:" + task.getTaskName());
                    //log.d(TAG, "loadingQueue.size=" + currentDownloads.size());
                    //添加进下载完毕队列
                    finishDownloads.offer(task);

                    if (wattingDownloads != null && wattingDownloads.size() > 0) {
                        //log.d(TAG, "从等待队列取出一个任务执行，并添加到下载队列");
                        MulThreadDownloader downloader = wattingDownloads.take();
                        currentDownloads.offer(downloader);
                        synchronized (obj) {
                            obj.notify();
                        }
                        //从监听器等待队列中拿出下个任务的监听器
//                        Iterator<String> it = wattingDownListeners.keySet().iterator();
//                        while (it.hasNext()) {
//                            String key = it.next();
//                            Log.d(TAG, "判断等待的监听队列中是否有下个任务的监听器 key=" + key + " nextListener=" + downloader.getTaskName());
//                            if (key.contains(downloader.getTaskName())) {
//                                currentDownListeners.put(downloader.getTaskName(), wattingDownListeners.get(key));
//                                wattingDownListeners.remove(key);
//                            }
                    }

//                    if (currentDownListeners != null && currentDownListeners.size() > 0) {
//                        Iterator<String> it = currentDownListeners.keySet().iterator();
//                        String taskKey = task.getTaskName();
//                        List<String> needDeleteKey = new ArrayList<String>();
//                        while (it.hasNext()) {
//                            String key = (String) it.next();
//                            if (key.contains(taskKey)) {
//                                Log.d(TAG, "will delete listener when " + key + " contains:" + taskKey);
//                                needDeleteKey.add(key);
//                            }
//                        }
//                        int keySize = needDeleteKey.size();
//                        for (int i = 0; i < keySize; i++) {
//                            String deleteKey = needDeleteKey.get(i);
//                            if (currentDownListeners.containsKey(deleteKey)) {
//                                currentDownListeners.remove(deleteKey);
//                            }
//                        }
//                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 检测任务下载状态
     *
     * @param downTask
     */
    private void checkTaskStatus(MulThreadDownloader downTask, DownloadProgressListener listener) {
        try {
            //如果此任务在正在下载队列中，nothing
            if (currentDownloads != null && checkGameIsDown(downTask.getTaskName())) {
                //log.d(TAG, "this task: " + downTask.getTaskName() + "is in loadingQueue,cant loading again");
                return;
            }
            // 如果正在下载队列没有达到最大队列数
            // 执行本次下载
            if (currentDownloads.size() < MAX_APK_DOWN_NUM) {
                //log.d(TAG, "offer " + downTask.getTaskName() + "to loadingQueue，prepare to down");
                downTask.setNewThread(true);
                if (currentDownloads == null)
                    currentDownloads = new ArrayBlockingQueue<MulThreadDownloader>(MAX_APK_DOWN_NUM, true);
                currentDownloads.offer(downTask);
                addDownListener(listener);
                synchronized (obj) {
                    //log.d(TAG, "notify dispatch thread ");
                    obj.notify();
                }
            } else {
                //判断这个任务是否在等待队列中，如果不在队列中，添加进等待队列
                boolean isExistsTask = checkGameIsWatting(downTask.getTaskName());
                if (!isExistsTask) {
                    //log.d(TAG, "off task:" + downTask.getTaskName() + "to waittingQueue");
                    wattingDownloads.offer(downTask);
                    addDownListener(listener);
                    //                wattingDownListeners.put(downTask.getTaskName(), listener);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 单线程下载
     *
     * @param context
     * @param url                      下载地址
     * @param path                     apk存放路径
     * @param apkSize                  apk大小
     * @param downloadProgressListener
     */
    public void startBySingleThread(final Context context, final String url,
                                    final String path, final long apkSize,
                                    final DownloadProgressListener downloadProgressListener) {
        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    singleDownLoader = new SingleThreadDownloader();
                    singleDownLoader.download(context, url, path, apkSize,
                            downloadProgressListener);
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * 检测游戏是否正在下载
     *
     * @param gamePkg 游戏包名
     * @return true：正在下载 false:没有下载
     */
    public boolean checkGameIsDown(String gamePkg) {
        boolean isDown = false;
        Iterator<MulThreadDownloader> it = currentDownloads.iterator();
        while (it.hasNext()) {
            MulThreadDownloader task = it.next();
            if (task.getTaskName().equals(gamePkg)) {
                isDown = true;
            }
        }
        return isDown;
    }

    /**
     * 添加listener
     *
     * @param listener 下载监听Listener
     */
    public static void addDownListener(DownloadProgressListener listener) {
        if (currentDownListeners != null && listener != null) {
            currentDownListeners.add(listener);
        }
    }

    /**
     * 删除 key为 tag的监听器
     *
     */
    public static void unRegistListener(DownloadProgressListener listener) {
        if (currentDownListeners != null && currentDownListeners.size() > 0) {
            currentDownListeners.remove(listener);
        }
    }

    /**
     * 重新下载，将暂停队列中的任务取出来放到正在下载队列中，直到正在下载队列size达到规定size
     * 如果暂停队列中的size超出了正在下载的size，将多余的任务放进等待队列
     */
    public void reDown() {

        try {
            if (pauseDownloads != null) {
                int size = pauseDownloads.size();
                //log.d(TAG, "redown " + size + " down task");
                //先查看正在下载队列的任务数是否超过 最大下载任务数
                if (currentDownloads.size() >= MAX_APK_DOWN_NUM) {
                    //log.d(TAG, "loading queue is full,offer task to wattingQueue");
                    //如果超过，则将所有的任务放进 等待下载队列
                    for (int i = 0; i < size; i++) {
                        MulThreadDownloader currentDownloader = pauseDownloads.poll();
                        wattingDownloads.offer(currentDownloader);
                        //log.d(TAG, "offer task to wattingQueue,wattingQueue size:" + wattingDownloads.size() + " pauseQueue size:" + pauseDownloads.size());
                    }
                } else {
                    for (int i = 0; i < size; i++) {
                        MulThreadDownloader currentDownloader = pauseDownloads.poll();
                        if (currentDownloads.size() <= MAX_APK_DOWN_NUM) {
                            currentDownloader.reDown();
                            currentDownloads.offer(currentDownloader);
                            synchronized (obj) {
                                //log.d(TAG, "notify dispatch thread ");
                                obj.notify();
                            }
                            //log.d(TAG, "offer task to loadingQueue,loadingQueue size:" + currentDownloads.size() + " pauseQueue size:" + pauseDownloads.size());
                        } else {
                            wattingDownloads.offer(currentDownloader);
                            //log.d(TAG, "offer task to wattingQueue,wattingQueue size:" + wattingDownloads.size() + " pauseQueue size:" + pauseDownloads.size());
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 重新下载包名为pkg的游戏
     *
     */
    public synchronized void reDown(DownRecord_Schema downRecord_schema) {


        if (downRecord_schema == null)
            throw new NullPointerException("the argument can't be null");
        try {
            String pkg = downRecord_schema.getApk_pkg();
            if (pauseDownloads != null) {
                //log.d(TAG, "redown down task when taskName = " + pkg);
                //先查看正在下载队列的任务数是否超过 最大下载任务数
                if (currentDownloads.size() >= MAX_APK_DOWN_NUM) {
                    //log.d(TAG, "loading queue is full,offer task to wattingQueue");
                    Iterator<MulThreadDownloader> it = pauseDownloads.iterator();
                    while (it.hasNext()) {
                        MulThreadDownloader currentDownloader = (MulThreadDownloader) it.next();
                        if (currentDownloader.getTaskName().equals(pkg)) {
                            wattingDownloads.offer(currentDownloader);
                            pauseDownloads.remove(currentDownloader);
                            //log.d(TAG, "offer task to wattingQueue,wattingQueue size:" + wattingDownloads.size() + " pauseQueue size:" + pauseDownloads.size());
                            break;
                        }
                    }
                } else {
                    //log.d(TAG, "pauseQueue size:" + pauseDownloads.size());
                    if (pauseDownloads.size() > 0) {
                        Iterator<MulThreadDownloader> it = pauseDownloads.iterator();
                        while (it.hasNext()) {
                            MulThreadDownloader currentDownloader = (MulThreadDownloader) it.next();
                            //log.d(TAG, "currentDownloader taskName:" + currentDownloader.getTaskName() + " redownTaskName:" + pkg);
                            if (currentDownloader.getTaskName().equals(pkg)) {
                                currentDownloader.reDown();
                                currentDownloads.offer(currentDownloader);
                                synchronized (obj) {
                                    //log.d(TAG, "notify dispatch thread ");
                                    obj.notify();
                                }
                                pauseDownloads.remove(currentDownloader);
                                //log.d(TAG, "offer task to loadingQueue,loadingQueue size:" + currentDownloads.size() + " pauseQueue size:" + pauseDownloads.size());
                                break;
                            }
                        }
                    } else {
                        MulThreadDownloader mulDownloader = null;

                        String savePath = getSavePath(ApkDownService.this);
                        mulDownloader = new MulThreadDownloader(ApkDownService.this, savePath,
                                downRecord_schema.getApk_pkg(),
                                downRecord_schema,
                                new MyLoadListener(downRecord_schema.getApk_pkg()));
                        currentDownloads.offer(mulDownloader);
                        synchronized (obj) {
                            obj.notify();
                        }
                        //log.d(TAG, "create new task, task name:" + downRecord_schema.getApk_pkg());

                    }

                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 暂停下载包名为pkg的游戏
     *
     * @param pkg 游戏包名
     */
    public void pauseDown(String pkg) {
        if (pkg == null || pkg.trim().length() <= 0)
            throw new NullPointerException("the pkg can't be null");

        int size = currentDownloads.size();
        //log.d(TAG, "start pause down task when pkg = " + pkg);
        Iterator<MulThreadDownloader> it = currentDownloads.iterator();
        while (it.hasNext()) {
            MulThreadDownloader currentDownloader = (MulThreadDownloader) it.next();
            if (currentDownloader.getTaskName().equals(pkg)) {
                //log.d(TAG, "task " + pkg + " will pause,pauseQueue size:" + pauseDownloads.size() + " loadingQueue size:" + currentDownloads.size());
                currentDownloader.pauseDown();
                pauseDownloads.offer(currentDownloader);
                currentDownloads.remove(currentDownloader);
                //log.d(TAG, "offer task to pauseQueue,pauseQueue size:" + pauseDownloads.size() + " loadingQueue size:" + currentDownloads.size());
                break;
            }
        }

    }

    /**
     * 暂停全部下载下载
     */
    public void pauseAll() {
        int size = currentDownloads.size();
        //log.d(TAG, "start pause " + size + " down task");
        for (int i = 0; i < size; i++) {
            MulThreadDownloader currentDownloader = currentDownloads.poll();
            currentDownloader.pauseDown();
            pauseDownloads.offer(currentDownloader);
            //log.d(TAG, "offer task to pauseQueue,pauseQueue size:" + pauseDownloads.size() + " loadingQueue size:" + currentDownloads.size());
        }

    }

    /**
     * 关闭下载
     */
    public void finish()
    {
        this.isCancel = true;
        cancelAll();
    }

    /**
     * 取消全部下载
     */
    public void cancelAll() {

        if (singleDownLoader != null) {
            singleDownLoader.cancelAllDownload();
        }

        if (currentDownloads != null) {
            for (MulThreadDownloader downloader : currentDownloads) {
                downloader.cancelAll();
            }
            currentDownloads.clear();
        }

        if (currentDownListeners != null) {
            currentDownListeners.clear();
        }

        if (wattingDownloads != null) {
            wattingDownloads.clear();
        }
    }

    /**
     * 获取所有下载过的apk信息
     *
     * @return
     */
    public static Map<String, DownRecord_Schema> getAllDownedApk() {
        Map<String, DownRecord_Schema> map = null;
        try {

            map = new HashMap<String, DownRecord_Schema>();
            List<Object> list = DbFastControl.getDbFast().queryAll(
                    DownRecord_Schema.class);
            if (list != null && list.size() > 0) {
                for (Object object : list) {
                    DownRecord_Schema schema = (DownRecord_Schema) object;
                    map.put(schema.getApk_pkg(), schema);
                }
            }
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return map;
    }

    /**
     * 根据包名查询 下载记录数据库中是否有次apk下载信息
     *
     * @param pkg
     * @return
     */
    public static DownRecord_Schema getDownApk(String pkg) {
        DownRecord_Schema downRecord_Schema = null;
        try {
            List<Object> list = DbFastControl.getDbFast().query(
                    DownRecord_Schema.class, "apk_pkg=?", new String[]{pkg});
            if (list != null && list.size() > 0) {
                downRecord_Schema = (DownRecord_Schema) list.get(0);
                if (downRecord_Schema.getDownState() == DownRecord_Schema.APK_STATE_DOWN_PAUSE) {

                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return downRecord_Schema;
    }


    /**
     * 检测此游戏是否在等待下载队列当中
     *
     * @param gamePkg
     * @return
     */
    private boolean checkGameIsWatting(String gamePkg) {
        boolean isExistsTask = false;
        int wattingSize = wattingDownloads.size();
        Iterator<MulThreadDownloader> it = wattingDownloads.iterator();
        while (it.hasNext()) {
            MulThreadDownloader task = it.next();
            if (task.getTaskName() == gamePkg) {
                isExistsTask = true;
            }
        }
        return isExistsTask;
    }

    /**
     * 获取某个游戏的绝对路径
     *
     * @param context
     * @param apkName
     * @return
     */
    public static String getLocalApkPath(Context context, String apkName) {
        String path = getSavePath(context);
        File saveFilePath = new File(path + File.separator + apkName + ".apk");
        return saveFilePath.getAbsolutePath();
    }

    public static String getSavePath(Context context) {
        File file = null;
        try {
            file = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        } catch (Exception e) {
            e.printStackTrace();
        }
        String path = null;
        if (file == null) {
            path = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "ipay";
        } else {
            if (!file.exists() || !file.isDirectory()) {
                file.mkdirs();
            }
            path = file + File.separator + "ipay";
        }

        return path;
    }

    /**
     * 安装应用
     *
     * @param filePath
     */
    private void installApk(String filePath) {
        try {
            File cacheFile = new File(filePath);
            if (cacheFile.exists()) {
                // 非wifi网络，或者wifi网络但是非静默下载，直接弹出安装界面
                Intent intent = new Intent(
                        Intent.ACTION_VIEW);
                intent.setDataAndType(
                        Uri.fromFile(cacheFile),
                        "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(ApkDownService.this,"安装失败,文件不存在", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            //log.e(TAG, e.getMessage());
        }
    }
}
