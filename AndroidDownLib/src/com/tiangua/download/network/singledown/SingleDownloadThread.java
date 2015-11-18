package com.tiangua.download.network.singledown;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.tiangua.download.callback.DownloadProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

public class SingleDownloadThread extends Thread
{
    private static final String TAG = "DownloadThread";
    /** 缓冲区 */
    private static final int BUFF_SIZE = 1024;

    private int connectTimeout = 30 * 1000;

    private int readTimeout = 30 * 1000;

    private Context context;
    /** 需要下载的URL */
    private String downUrl;
    /** 缓存的FIle */
    private String saveFile;
    /** 完成 */
    private boolean finished = false;
    /** 取消下载 */
    private boolean isCancel = false;
    /** 已下载多少数据 **/
    private long downloadSize = 0l;

    private DownloadProgressListener listener;
    private Proxy mProxy;

    public SingleDownloadThread(Context context, String strurl, String path,
            DownloadProgressListener listener)
    {
        this.context = context;
        this.downUrl = strurl;
        this.saveFile = path;
        this.listener = listener;
    }
   

    @Override
    public void run()
    {
        //
        detectProxy(context);

        try
        {
            URL url = new URL(downUrl);
            HttpURLConnection conn = null;
            if (mProxy != null)
            {
                conn = (HttpURLConnection) url.openConnection(mProxy);
            }
            else
            {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoInput(true);

            conn.connect();
            InputStream is = conn.getInputStream();

            File file = new File(saveFile);
            if(file.exists() && file.isFile())
                file.delete();
            file.createNewFile();
            FileOutputStream fos = new FileOutputStream(file);

            if (listener != null)
            {
                listener.onDownStart("");
            }
            byte[] temp = new byte[1024];
            int i = 0;
            while ((i = is.read(temp)) > 0)
            {
                if(isCancel)
                {
                    System.out.println("取消下载");
                    break;
                }
                fos.write(temp, 0, i);
                downloadSize += i;
//                Log.d("SingleThreadDownloadThread", "DownLoadSize = "+downloadSize);
            }
//            Log.d("SingleThreadDownloadThread", "AllDownLoadSize = "+downloadSize);
            fos.close();
            is.close();
            finished = true;
        }
        catch (IOException e)
        {
            e.printStackTrace();
            finished = true;
            downloadSize = -1;
        }
    }

    public void cancelDown()
    {
        isCancel = true;
    }
    
    public boolean isFinish()
    {
        return finished;
    }

    public long getDownSize()
    {
        return downloadSize;
    }

    /**
     * 检查代理，是否cnwap接入
     */
    private void detectProxy(Context mContext)
    {
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni != null && ni.isAvailable()
                && ni.getType() == ConnectivityManager.TYPE_MOBILE)
        {
            String proxyHost = android.net.Proxy.getDefaultHost();
            int port = android.net.Proxy.getDefaultPort();
            if (proxyHost != null)
            {
                final InetSocketAddress sa = new InetSocketAddress(proxyHost,
                                                                   port);
                mProxy = new Proxy(Proxy.Type.HTTP, sa);
            }
        }
    }

    private void setDefaultHostnameVerifier()
    {
        //
        HostnameVerifier hv = new HostnameVerifier() {

            @Override
            public boolean verify(String arg0, SSLSession arg1)
            {
                // TODO Auto-generated method stub
                return false;
            }
        };

        HttpsURLConnection.setDefaultHostnameVerifier(hv);
    }
}
