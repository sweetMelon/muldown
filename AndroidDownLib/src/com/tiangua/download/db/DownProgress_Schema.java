package com.tiangua.download.db;

/**
 *  下载进度schema，存放apk的每条下载线程的对应下载进度
 * @author Administrator
 *
 */
public class DownProgress_Schema
{
    private int _id;
    private String apk_name; //apk名称
    private String apk_url;
    private String apk_pkg; //apk包名
    private int    down_thread_id; //下载线程id
    private long   down_length;//下载进度
    private long   down_block;//本条下载线程可下载的最大长度
    private int deleteTag;
    public int get_id()
    {
        return _id;
    }
    public String getApk_name()
    {
        return apk_name;
    }
    public void setApk_name(String apk_name)
    {
        this.apk_name = apk_name;
    }
    public int getDown_thread_id()
    {
        return down_thread_id;
    }
    public void setDown_thread_id(int down_thread_id)
    {
        this.down_thread_id = down_thread_id;
    }
    public long getDown_length()
    {
        return down_length;
    }
    public void setDown_length(long down_length)
    {
        this.down_length = down_length;
    }
    public String getPkg_name()
    {
        return apk_pkg;
    }
    public void setPkg_name(String pkg_name)
    {
        this.apk_pkg = pkg_name;
    }
    public String getApk_url()
    {
        return apk_url;
    }
    public void setApk_url(String apk_url)
    {
        this.apk_url = apk_url;
    }
    public long getDown_block()
    {
        return down_block;
    }
    public void setDown_block(long down_block)
    {
        this.down_block = down_block;
    }
    
}   
