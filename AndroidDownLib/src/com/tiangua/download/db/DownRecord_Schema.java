package com.tiangua.download.db;

public class DownRecord_Schema {
    public static final int APK_STATE_DOWNLOADING = 1;// 下载中
    public static final int APK_STATE_DOWNLOADED = 2;// 下载完成(未安装)
    public static final int APK_STATE_DOWN_INSATLL = 3;// 已安装
    public static final int APK_STATE_DOWN_ERROR = 4; // 下载失败
    public static final int APK_STATE_DOWN_PAUSE = 5; //暂停下载
    public static final int APK_STATE_DOWN_CANCEL = 6; //取消下载

    private int _id;
    private String apk_name = "";// apk名
    private String apk_pkg = "";// apk包名
    private String apk_url = "";// 下载地址
    private String apk_vn = "";// apk版本号
    private long apk_down_size = 0l; //apk已下载大小
    private long apk_Size = 0l; //apk大小
    private int downState = 0;// apk状态 0:未下载（默认） 1：下载中 2：下载完成(未安装) 3:已安装 4:下载失败
    private int deleteTag;

    public int get_id() {
        return _id;
    }

    public String getApk_name() {
        return apk_name;
    }

    public void setApk_name(String apk_name) {
        this.apk_name = apk_name;
    }

    public int getDownState() {
        return downState;
    }

    public void setDownState(int downState) {
        this.downState = downState;
    }

    public String getApk_pkg() {
        return apk_pkg;
    }

    public void setApk_pkg(String apk_pkg) {
        this.apk_pkg = apk_pkg;
    }

    public String getApk_vn() {
        return apk_vn;
    }

    public void setApk_vn(String apk_vn) {
        this.apk_vn = apk_vn;
    }

    public String getApk_url() {
        return apk_url;
    }

    public void setApk_url(String apk_url) {
        this.apk_url = apk_url;
    }

    public void setApkSize(long apkSize) {
        this.apk_Size = apkSize;
    }

    public long getApkSize() {
        return apk_Size;
    }

    public long getApkDownSize() {
        return apk_down_size;
    }

    public void setApkDownSize(long downSize) {
        this.apk_down_size = downSize;
    }
}
