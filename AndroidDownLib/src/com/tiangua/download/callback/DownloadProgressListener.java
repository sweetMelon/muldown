package com.tiangua.download.callback;

public interface DownloadProgressListener
{
    public void onDownStart(String pkg);
    /**
     * 
     * @param allDownLoadedSize 当前下载的全部大小
     * @param downloadSpeed 下载速度 kb/秒
     * @param downLoadPrecent 下载百分比
     */
    public void onDownloading(String pkg, long allDownLoadedSize, long downloadSpeed, long downLoadPrecent);
    
    public void onDownloadFinish(String pkg, String saveFile);
    
    public void onDownFailed(String pkg, String error);
    
    public void onDownCancel(String pkg);

    /**
     * 暂停下载
     */
    public void onDownPause(String pkg);
}
