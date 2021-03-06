/*
 * Copyright (C) 2014 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.network;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.webkit.MimeTypeMap;

import com.hippo.ehviewer.AppHandler;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ehclient.EhClient;
import com.hippo.ehviewer.ehclient.EhInfo;
import com.hippo.ehviewer.exception.StopRequestException;
import com.hippo.ehviewer.util.BgThread;
import com.hippo.ehviewer.util.Config;
import com.hippo.ehviewer.util.Constants;
import com.hippo.ehviewer.util.EhUtils;
import com.hippo.ehviewer.util.FastByteArrayOutputStream;
import com.hippo.ehviewer.util.Log;
import com.hippo.ehviewer.util.MathUtils;
import com.hippo.ehviewer.util.Ui;
import com.hippo.ehviewer.util.Utils;

import org.apache.http.conn.ConnectTimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * It is not thread safe
 *
 * @author Hippo
 *
 */
public class HttpHelper {
    private static final String TAG = HttpHelper.class.getSimpleName();

    public static final String SAD_PANDA_ERROR = "Sad Panda";
    public static final String HAPPY_PANDA_BODY = "Happy Panda";

    public static final String DOWNLOAD_OK_STR = "OK";

    public static final int DOWNLOAD_OK_CODE = 0x0;
    public static final int DOWNLOAD_FAIL_CODE = 0x1;
    public static final int DOWNLOAD_STOP_CODE = 0x2;

    // User-Agent from H@H
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.8.1.12) " +
            "Gecko/20080201 Firefox/2.0.0.12";
    public static final String USER_AGENT = DEFAULT_USER_AGENT;
            //System.getProperty("http.agent", DEFAULT_USER_AGENT);

    private static final String DEFAULT_CHARSET = "utf-8";
    private static final String CHARSET_KEY = "charset=";

    // TODO Get proxy list from server
    private static String[] sProxyUrls = Config.DEFAULT_PROXY_URLS;
    private static volatile int sProxyIndex = MathUtils.random(0, sProxyUrls.length);
    private static final Object sProxyIndexLock = new Object();

    public class Package {
        public Object obj;
        public OnRespondListener listener;
        public Package(Object obj, OnRespondListener listener) {
            this.obj = obj;
            this.listener = listener;
        }
    }

    private final Context mContext;
    private Exception mException;
    private String mLastUrl;
    private OnRespondListener mListener;
    private String mPreviewMode;

    private volatile long mLastEhConnectTime = 0;
    private static final Object ehLock = new Object();

    public interface OnRespondListener {
        void onSuccess(Object body);
        void onFailure(String eMsg);
    }

    public static void updateProxyUrls(final Context context) {
        new BgThread() {
            @Override
            public void run() {
                try {
                    HttpHelper hh = new HttpHelper(context);
                    JSONObject json = new JSONObject();
                    json.put("method", "proxy_urls");
                    String body = hh.postJson(EhClient.API_EHVIEWER, json);
                    JSONObject jo = new JSONObject(body);
                    JSONArray ja = jo.getJSONObject("proxy_urls").getJSONArray("urls");
                    String[] newUrls = new String[ja.length()];
                    if (newUrls.length < 1)
                        throw new Throwable();
                    for (int i = 0; i < ja.length(); i++)
                        newUrls[i] = ja.getString(i);
                    synchronized(sProxyIndexLock) {
                        sProxyUrls = newUrls;
                        sProxyIndex = MathUtils.random(0, sProxyUrls.length);
                    }
                    // Put proxy into config
                    Config.setProxyUrls(sProxyUrls);
                } catch (Throwable e) {
                    synchronized(sProxyIndexLock) {
                        sProxyUrls = Config.getProxyUrls();
                        sProxyIndex = MathUtils.random(0, sProxyUrls.length);
                    }
                }
            }
        }.start();
    }

    public String getProxyUrl() {
        synchronized(sProxyIndexLock) {
            if (sProxyIndex < 0 || sProxyIndex >= sProxyUrls.length)
                sProxyIndex = 0;
            return sProxyUrls[sProxyIndex++];
        }
    }

    public void reset() {
        mListener = null;
        mException = null;
        mLastUrl = null;
        mPreviewMode = null;
    }

    public void setOnRespondListener(OnRespondListener l) {
        mListener = l;
    }

    public HttpHelper(Context context) {
        mContext = context;
        mContext.getApplicationContext();
    }

    /**
     * Get last error message
     * @return
     */
    public String getEMsg() {
        return getEMsg(mContext, mException);
    }

    public Exception getException() {
        return mException;
    }

    public static String getEMsg(Context c, Exception e) {
        if (e == null)
            return c.getString(R.string.em_unknown_error);

        else if (e instanceof MalformedURLException)
            return c.getString(R.string.em_url_format_error) + ": " + e.getMessage();

        else if (e instanceof ConnectTimeoutException ||
                e instanceof SocketTimeoutException)
            return c.getString(R.string.em_timeout) + ": " + e.getMessage();

        else if (e instanceof UnknownHostException)
            return c.getString(R.string.em_unknown_host) + ": " + e.getMessage();

        else if (e instanceof ResponseCodeException)
            return String.format(c.getString(R.string.em_unexpected_response_code),
                    ((ResponseCodeException)e).getResponseCode())  + ": " + e.getMessage();

        else if (e instanceof RedirectionException)
            return c.getString(R.string.em_redirection_error)  + ": " + e.getMessage();

        else if (e instanceof SocketException)
            return "SocketException: " + e.getMessage();

        else if (e instanceof SadPandaException)
            return SAD_PANDA_ERROR;

        else if (e instanceof GetBodyException)
            return c.getString(R.string.em_requst_null);

        else
            return e.getClass().getSimpleName() + ": " +e.getMessage();
    }

    public String getLastUrl() {
        return mLastUrl;
    }

    public void setPreviewMode(String previewMode) {
        mPreviewMode = previewMode;
    }

    private Object requst(RequestHelper rh) {
        URL url = null;
        HttpURLConnection conn = null;
        boolean isCookiable = false;

        for (int times = 0; times < Config.getHttpRetry(); times++) {
            mException = null;
            int redirectionCount = 0;
            boolean firstTime = true;
            try {
                url = (mLastUrl == null ? rh.getUrl() : new URL(mLastUrl));

                isCookiable = EhInfo.isEhSite(url);

                while (isCookiable) {
                    synchronized(ehLock) {
                        long cur = System.currentTimeMillis();
                        long left = mLastEhConnectTime + Config.getEhMinInterval() - cur;
                        if (left <= 0) {
                            mLastEhConnectTime = cur;
                            break;
                        } else {
                            Thread.sleep(left);
                        }
                    }
                }

                Log.d(TAG, "Requst " + url.toString());
                while (redirectionCount++ < Constants.MAX_REDIRECTS) {
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setConnectTimeout(Config.getHttpConnectTimeout());
                    conn.setReadTimeout(Config.getHttpReadTimeout());
                    // Set cookie if necessary
                    if (isCookiable)
                        EhInfo.getInstance(mContext).setCookie(conn, mPreviewMode);
                    // Do custom staff
                    if (firstTime) {
                        rh.onBeforeConnect(conn);
                        firstTime = false;
                    }

                    conn.connect();
                    final int responseCode = conn.getResponseCode();
                    switch (responseCode) {
                    case HttpURLConnection.HTTP_OK:
                    case HttpURLConnection.HTTP_PARTIAL:
                        // Test sad panda
                        if (url.getHost().equals(EhInfo.EX_HOST)) {
                            String contentType = conn.getContentType();
                            if (contentType != null && contentType.equals("image/gif")
                                    && conn.getContentLength() == 9615)
                                throw new SadPandaException();
                        }
                        // Store cookie if necessary
                        if (isCookiable)
                            EhInfo.getInstance(mContext).storeCookie(conn);
                        // Get object connection
                        Object obj = rh.onAfterConnect(conn);
                        // Send to UI thread if necessary
                        if (mListener != null)
                            AppHandler.getInstance().sendMessage(
                                    Message.obtain(null, AppHandler.HTTP_HELPER_TAG,
                                            Constants.TRUE, 0, new Package(obj, mListener)));

                        return obj;
                    // redirect
                    case HttpURLConnection.HTTP_MOVED_PERM:
                    case HttpURLConnection.HTTP_MOVED_TEMP:
                    case HttpURLConnection.HTTP_SEE_OTHER:
                    case Constants.HTTP_TEMP_REDIRECT:
                        final String location = conn.getHeaderField("Location");
                        Log.d(TAG, "New location " + location);
                        mLastUrl = location;
                        conn.disconnect();
                        url = new URL(url, location);
                        continue;

                    default:
                        String body;
                        // Get pure text error
                        if (rh instanceof GetStringHelper
                                && (body = (String)rh.onAfterConnect(conn)) != null
                                && !body.contains("<"))
                            throw new Exception(body);
                        else
                            throw new ResponseCodeException(responseCode);
                    }
                }
                throw new RedirectionException();
            } catch (Exception e) {
                mException = e;
                e.printStackTrace();
                // For download, if need stop, just stop
                if (mException instanceof StopRequestException)
                    break;
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }

        // Send to UI thread if necessary
        if (mListener != null)
            AppHandler.getInstance().sendMessage(
                    Message.obtain(null, AppHandler.HTTP_HELPER_TAG,
                            Constants.FALSE, 0, new Package(getEMsg(), mListener)));
        // Send exception to helper
        if (mException != null)
            rh.onGetException(mException);

        return null;
    }

    /**
     * parse string like <code>haha=hehe; fere=bfdgds</code>
     */
    public static @NonNull Map<String, String> parseMap(@Nullable String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw != null) {
            String[] pieces = raw.split(";");
            for (String p : pieces) {
                int index = p.indexOf('=');
                if (index != -1) {
                    String key = p.substring(0, index).trim();
                    String value = p.substring(index + 1).trim();

                    // value might be "blabla", remove "
                    int valueLength = value.length();
                    if (value.length() > 1 && value.charAt(0) == '"' &&
                            value.charAt(valueLength - 1) == '"') {
                        value = value.substring(1, valueLength - 1);
                    }
                    map.put(key.toLowerCase(), value);
                }
            }
        }

        return map;
    }

    public static @Nullable String getMime(HttpURLConnection conn) {
        String contentType = conn.getContentType();
        // looks like text/html; charset=ISO-8859-4 or text/html
        int index = contentType.indexOf(';');
        if (index != -1) {
            return contentType.substring(0, index);
        } else {
            return contentType;
        }
    }

    private interface RequestHelper {

        /**
         * Get the URL to connect
         * @return
         */
        URL getUrl() throws MalformedURLException;

        /**
         * Add header or do something else for HttpURLConnection before connect
         * @param conn
         * @throws Exception
         */

        void onBeforeConnect(HttpURLConnection conn) throws Exception;

        /**
         * Get what do you need from HttpURLConnection after connect
         * Return null means get error
         * @param conn
         * @return
         * @throws Exception
         */
        Object onAfterConnect(HttpURLConnection conn) throws Exception;

        /**
         * Handle exception
         * @param e
         */
        void onGetException(Exception e);
    }

    /**
     * RequstHelper for check sad panda, use HEAD method
     */
    private class CheckSpHelper implements RequestHelper {
        @Override
        public URL getUrl() throws MalformedURLException {
            return new URL(EhClient.HEADER_EX);
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn)
                throws Exception {
            conn.setRequestMethod("HEAD");
        }

        @Override
        public Object onAfterConnect(HttpURLConnection conn)
                throws Exception {
            return HAPPY_PANDA_BODY;
        }

        @Override
        public void onGetException(Exception e) {}
    }

    private abstract class GetStringHelper implements RequestHelper {
        private final String mUrl;

        public GetStringHelper(String url) {
            mUrl = url;
        }

        @Override
        public URL getUrl() throws MalformedURLException {
            return new URL(mUrl);
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn)
                throws Exception {
            conn.addRequestProperty("Accept-Encoding", "gzip");
        }

        private String getBody(HttpURLConnection conn)
                throws Exception {
            String body = null;
            InputStream is = null;
            ByteArrayOutputStream baos = null;
            try {
                is = conn.getInputStream();
                String encoding = conn.getContentEncoding();
                if (encoding != null && encoding.equals("gzip"))
                    is = new GZIPInputStream(is);

                int length = conn.getContentLength();
                if (length >= 0)
                    baos = new ByteArrayOutputStream(length);
                else
                    baos = new ByteArrayOutputStream();

                Utils.copy(is, baos, Constants.BUFFER_SIZE);

                // Get charset
                String charset = null;
                String contentType = conn.getContentType();
                int index = -1;
                if (contentType != null
                        && (index = contentType.indexOf(CHARSET_KEY)) != -1) {
                    charset = contentType.substring(index + CHARSET_KEY.length());
                } else
                    charset = DEFAULT_CHARSET;

                body = baos.toString(charset);
                if (body == null)
                    throw new GetBodyException();
            } catch (Exception e) {
                throw e;
            } finally {
                Utils.closeQuietly(is);
                Utils.closeQuietly(baos);
            }
            return body;
        }

        @Override
        public Object onAfterConnect(HttpURLConnection conn)
                throws Exception {
            return getBody(conn);
        }

        @Override
        public void onGetException(Exception e) {}
    }

    /**
     * RequstHelper for GET method
     */
    private class GetHelper extends GetStringHelper {

        public GetHelper(String url) {
            super(url);
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn)
                throws Exception {
            super.onBeforeConnect(conn);
            conn.setRequestMethod("GET");
        }
    }

    /**
     * RequstHelper for post form data, use POST method
     */
    private class PostFormHelper extends GetStringHelper {
        private final String[][] mArgs;

        public PostFormHelper(String url, String[][] args) {
            super(url);
            mArgs = args;
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn)
                throws Exception {
            super.onBeforeConnect(conn);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (String[] arg : mArgs) {
                if (i != 0)
                    sb.append("&");
                sb.append(URLEncoder.encode(arg[0], "UTF-8"));
                sb.append("=");
                sb.append(URLEncoder.encode(arg[1], "UTF-8"));
                i++;
            }
            out.writeBytes(sb.toString());
            out.flush();
            out.close();
        }
    }

    /**
     * RequstHelper for post json, use POST method
     */
    private class PostJsonHelper extends GetStringHelper {
        private final JSONObject mJo;

        public PostJsonHelper(String url, JSONObject jo) {
            super(url);
            mJo = jo;
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn) throws Exception {
            super.onBeforeConnect(conn);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.writeBytes(mJo.toString());
            out.flush();
            out.close();
        }
    }

    public abstract static class FormData {
        private final Map<String, String> mProperties;

        public FormData() {
            mProperties = new LinkedHashMap<String, String>();
        }

        public void setProperty(String key, String value) {
            mProperties.put(key, value);
        }

        public void clearProperty(String key) {
            mProperties.remove(key);
        }

        public void clearAllProperties() {
            mProperties.clear();
        }

        /**
         * Put information to target OutputStream.
         *
         * @param os Target OutputStream
         * @throws IOException
         */
        public abstract void output(OutputStream os) throws IOException;

        public void doOutPut(OutputStream os) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (String key : mProperties.keySet())
                sb.append(key).append(": ").append(mProperties.get(key)).append("\r\n");
            sb.append("\r\n");
            os.write(sb.toString().getBytes());
            output(os);
        }
    }

    public static class StringData extends FormData {
        private final String mStr;

        public StringData(String str) {
            mStr = str;
        }

        @Override
        public void output(OutputStream os) throws IOException {
            os.write(mStr.getBytes());
            os.write("\r\n".getBytes());
        }
    }

    public static class BitmapData extends FormData {
        private final Bitmap mBitmap;

        public BitmapData(Bitmap bmp) {
            mBitmap = bmp;
            setProperty("Content-Type", "image/jpeg");
        }

        @Override
        public void output(OutputStream os) throws IOException {
            mBitmap.compress(Bitmap.CompressFormat.JPEG, 98, os); //TODO I need a better quality
            os.write("\r\n".getBytes());
        }
    }

    public static class FileData extends FormData {
        private final File mFile;

        public FileData(File file) {
            mFile = file;
            setProperty("Content-Type",
                    URLConnection.guessContentTypeFromName(file.getName()));
        }

        @Override
        public void output(OutputStream os) throws IOException {
            InputStream is = new FileInputStream(mFile);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while((bytesRead = is.read(buffer)) !=-1)
                os.write(buffer, 0, bytesRead);
            is.close();

            os.write("\r\n".getBytes());
        }
    }

    private class PostFormDataHelper extends GetStringHelper {
        private static final String BOUNDARY = "------WebKitFormBoundary7eDB0hDQ91s22Tkf";

        private final List<FormData> mDataList;


        public PostFormDataHelper(String url, List<FormData> dataList) {
            super(url);
            mDataList = dataList;
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn) throws Exception {
            super.onBeforeConnect(conn);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundary7eDB0hDQ91s22Tkf");

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            //OutputStream out = new FileOutputStream(new File("/sdcard/a.txt"));

            for (FormData data : mDataList) {
                out.write(BOUNDARY.getBytes());
                out.write("\r\n".getBytes());
                data.doOutPut(out);
            }
            out.write(BOUNDARY.getBytes());
            out.write("--".getBytes());

            out.flush();
            out.close();
        }
    }

    private class GetImageHelper implements RequestHelper {
        private final String mUrl;

        public GetImageHelper(String url) {
            mUrl = url;
        }

        @Override
        public URL getUrl() throws MalformedURLException {
            return new URL(mUrl);
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn)
                throws Exception {
            conn.setRequestMethod("GET");
        }

        @Override
        public Object onAfterConnect(HttpURLConnection conn)
                throws Exception {
            // If just
            // Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream(), null, Ui.getBitmapOpt());
            // bitmap might be incomplete.
            int size = conn.getContentLength();
            FastByteArrayOutputStream fbaos = new FastByteArrayOutputStream(size == -1 ? 24 * 1024 : (size + 100));
            Utils.copy(conn.getInputStream(), fbaos);
            Bitmap bmp = BitmapFactory.decodeByteArray(fbaos.getBuffer(), 0, fbaos.size(), Ui.getBitmapOpt());

            if (bmp == null)
                throw new GetBodyException();
            return bmp;
        }

        @Override
        public void onGetException(Exception e) {}
    }

    public interface OnDownloadListener {
        void onDownloadStartConnect();
        /**
         * If totalSize -1 for can't get length info
         */
        void onDownloadStartDownload(int totalSize);
        /**
         * If totalSize -1 for can't get length info
         */
        void onDownloadStatusUpdate(int downloadSize, int totalSize);
        void onDownloadOver(int status, String eMsg);
        void onUpdateFilename(String newFilename);
    }

    public static class DownloadControlor {
        private boolean mStop = false;

        public void stop() {
            mStop = true;
        }

        public void reset() {
            mStop = false;
        }

        public boolean isStop() {
            return mStop;
        }
    }

    public static class DownloadOption {

        public boolean mAllowFixingName = false;
        public boolean mAllowFixingExtension = false;
        public boolean mUseProxy = false;

        public boolean isAllowFixingName() {
            return mAllowFixingName;
        }

        public void setAllowFixingName(boolean allowFixingName) {
            mAllowFixingName = allowFixingName;
        }

        public boolean isAllowFixingExtension() {
            return mAllowFixingExtension;
        }

        public void setAllowFixingExtension(boolean allowFixingExtension) {
            mAllowFixingExtension = allowFixingExtension;
        }

        public boolean isUseProxy() {
            return mUseProxy;
        }

        public void setUseProxy(boolean useProxy) {
            mUseProxy = useProxy;
        }
    }

    private class DownloadHelper implements RequestHelper {

        private static final String DOWNLOAD_EXTENSION = ".download";

        private @NonNull final String mUrl;
        private @NonNull final File mDir;
        private @NonNull String mFilename;
        private File mFile;
        private File mTempFile;
        private @NonNull DownloadOption mOption;
        private @Nullable final DownloadControlor mControlor;
        private @Nullable final OnDownloadListener mListener;
        private int mContentLength;
        private int mReceivedSize;

        public DownloadHelper(@NonNull String url, @NonNull File dir,
                @NonNull String filename, @Nullable DownloadOption option,
                @Nullable DownloadControlor controlor,
                @Nullable OnDownloadListener listener) {
            mUrl = url;
            mDir = dir;
            mFilename = filename;
            if (option == null) {
                mOption = new DownloadOption();
            } else {
                mOption = option;
            }
            mControlor = controlor;
            mListener = listener;
        }

        @Override
        public URL getUrl() throws MalformedURLException {
            return new URL(mOption.isUseProxy() ? getProxyUrl() : mUrl);
        }

        @Override
        public void onBeforeConnect(HttpURLConnection conn) throws Exception {
            if (mControlor != null && mControlor.isStop())
                throw new StopRequestException();

            if (mOption.isUseProxy()) {
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setUseCaches(false);
                conn.setRequestMethod("POST");

                DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                out.writeBytes("{\"url\":\"" + mUrl + "\"}");
                out.flush();
                out.close();
            } else {
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-");
            }

            if (mListener != null) {
                mListener.onDownloadStartConnect();
            }
        }

        private int getContentLength(HttpURLConnection conn) {
            int contentLength = conn.getContentLength();
            String range;
            if (contentLength == -1 &&
                    (range = conn.getHeaderField("Content-Range")) != null) {
                // Content-Range looks like bytes 500-999/1234
                contentLength = 0;
                int step = 0;
                boolean isNum = false;
                char ch;
                for (int i = 0; i < range.length(); i++) {
                    ch = range.charAt(i);
                    if (ch >= '0' && ch <= '9') {
                        if (!isNum) {
                            isNum = true;
                            step++;
                        }
                    } else {
                        isNum = false;
                    }
                    if (isNum && step == 3)
                        contentLength = contentLength * 10 + ch - '0';
                }
            }
            return contentLength;
        }

        public String getSuitableFilename(HttpURLConnection conn) {
            String contentDisposition = conn.getHeaderField("Content-Disposition");
            Map<String, String> map = parseMap(contentDisposition);
            return map.get("filename");
        }

        protected String onFixName(String newName) {
            return newName;
        }

        protected String onFixExtension(String newExtension) {
            return newExtension;
        }

        protected String fixFilename(HttpURLConnection conn) {
            String originalName;
            String originalExtension;
            String newName = null;
            String newExtension = null;
            String[] temp = new String[2];

            // get originalName and originalExtension
            Utils.getNameAndExtension(mFilename, temp);
            originalName = temp[0];
            originalExtension = temp[1];

            String suitableFilename = getSuitableFilename(conn);
            if (suitableFilename != null) {
                Utils.getNameAndExtension(suitableFilename, temp);
                newName = temp[0];
                newExtension = temp[1];
            } else {
                String mime = getMime(conn);
                if (mime != null) {
                    MimeTypeMap mimeMap = MimeTypeMap.getSingleton();
                    originalExtension = mimeMap.getExtensionFromMimeType(mime);
                }
            }

            if (newName == null) {
                newName = originalName;
            }
            if (mOption.isAllowFixingName()) {
                newName = onFixName(newName);
            } else {
                newName = originalName;
            }

            if (newExtension == null) {
                newExtension = originalExtension;
            }
            if (mOption.isAllowFixingExtension()) {
                newExtension = onFixExtension(newExtension);
            } else {
                newExtension = originalExtension;
            }

            String newFilename = newName;
            if (newExtension != null) {
                newFilename = newFilename + '.' + newExtension;
            }

            return newFilename;
        }


        @Override
        public Object onAfterConnect(HttpURLConnection conn) throws Exception {
            // Check stop
            if (mControlor != null && mControlor.isStop()) {
                throw new StopRequestException();
            }

            String oldFilename = mFilename;

            if (mOption.isAllowFixingName() || mOption.isAllowFixingExtension()) {
                mFilename = fixFilename(conn);
            }

            // TODO Should check first
            if (!oldFilename.equals(mFilename) && mListener != null) {
                mListener.onUpdateFilename(mFilename);
            }

            mContentLength = getContentLength(conn);
            if (mListener != null)
                mListener.onDownloadStartDownload(mContentLength);

            // Make sure parent exist
            mDir.mkdirs();
            mFile = new File(mDir, mFilename);
            mTempFile = new File(mDir, mFilename + DOWNLOAD_EXTENSION);
            // Transfer
            transferData(conn.getInputStream(), new FileOutputStream(mTempFile));
            // Get ok, rename
            mTempFile.renameTo(mFile);
            // Callback
            if (mListener != null)
                mListener.onDownloadOver(DOWNLOAD_OK_CODE, null);
            return DOWNLOAD_OK_STR;
        }

        @Override
        public void onGetException(Exception e) {
            // Delete unfinished file
            if (mTempFile != null)
                mTempFile.delete();
            if (mFile != null)
                mFile.delete();
            // Callback
            if (mListener != null) {
                if (e instanceof StopRequestException)
                    mListener.onDownloadOver(DOWNLOAD_STOP_CODE, getEMsg());
                else
                    mListener.onDownloadOver(DOWNLOAD_FAIL_CODE, getEMsg());
            }
        }

        private void transferData(InputStream in, OutputStream out)
                throws Exception {
            final byte data[] = new byte[Constants.BUFFER_SIZE];
            mReceivedSize = 0;

            while (true) {
                // Check stop first
                if (mControlor != null && mControlor.isStop())
                    throw new StopRequestException();

                int bytesRead = in.read(data);
                if (bytesRead == -1)
                    break;
                out.write(data, 0, bytesRead);
                mReceivedSize += bytesRead;
                if (mListener != null)
                    mListener.onDownloadStatusUpdate(mReceivedSize, mContentLength);
            }

            if (mContentLength != -1 && mReceivedSize != mContentLength)
                throw new UncompletedException("Received size is " + mReceivedSize
                        + ", but ContentLength is " + mContentLength);
        }
    }


    private class DownloadEhImageHelper extends DownloadHelper {

        public DownloadEhImageHelper(@NonNull String url, @NonNull File dir,
                @NonNull String filename, @Nullable DownloadOption option,
                @Nullable DownloadControlor controlor,
                @Nullable OnDownloadListener listener) {
            super(url, dir, filename, option, controlor, listener);
        }

        @Override
        protected String onFixExtension(String ext) {
            int length = EhUtils.POSSIBLE_IMAGE_EXTENSION_ARRAY.length;
            int i;
            for (i = 0; i < length; i++) {
                if (EhUtils.POSSIBLE_IMAGE_EXTENSION_ARRAY[i].equals(ext)) {
                    break;
                }
            }
            if (i == length) {
                ext = EhUtils.DEFAULT_IMAGE_EXTENSION;
            }
            return ext;
        }
    }

    private class DownloadOriginEhImageHelper extends DownloadEhImageHelper {

        public DownloadOriginEhImageHelper(@NonNull String url, @NonNull File dir,
                @NonNull String filename, @Nullable DownloadOption option,
                @Nullable DownloadControlor controlor,
                @Nullable OnDownloadListener listener) {
            super(url, dir, filename, option, controlor, listener);
        }

        @Override
        public Object onAfterConnect(HttpURLConnection conn) throws Exception {
            if (mLastUrl == null) {
                throw new BandwidthExceededException();
            }

            return super.onAfterConnect(conn);
        }
    }


    /**
     * Check Sad Panda.
     * If get Sad Panda, return null,
     * else return HttpHelper.HAPPY_PANDA_BODY
     * @return
     */
    public String checkSadPanda() {
        return (String)requst(new CheckSpHelper());
    }

    /**
     * Http GET method
     * @param url
     * @return
     */
    public String get(String url) {
        return (String)requst(new GetHelper(url));
    }

    /**
     * Post form data
     * @param url
     * @param args
     * @return
     */
    public String postForm(String url, String[][] args) {
        return (String)requst(new PostFormHelper(url, args));
    }

    /**
     * Post json data
     * @param url
     * @param json
     * @return
     */
    public String postJson(String url, JSONObject json) {
        return (String)requst(new PostJsonHelper(url, json));
    }

    /**
     * Post form data, multipart/form-data
     * @param url
     * @param dataList
     * @return
     */
    public String postFormData(String url, List<FormData> dataList) {
        return (String)requst(new PostFormDataHelper(url, dataList));
    }

    /**
     * Get image
     * @param url
     * @return
     */
    public Bitmap getImage(String url) {
        return (Bitmap) requst(new GetImageHelper(url));
    }

    public String download(@NonNull String url, @NonNull File dir,
            @NonNull String filename, @Nullable DownloadOption option,
            @Nullable DownloadControlor controlor,
            @Nullable OnDownloadListener listener) {
        return (String) requst(new DownloadHelper(url, dir, filename,
                option, controlor, listener));
    }

    public void downloadInThread(@NonNull final String url, @NonNull final File dir,
            @NonNull final String filename, @Nullable final DownloadOption option,
            @Nullable final DownloadControlor controlor,
            @Nullable final OnDownloadListener listener) {
        new BgThread() {
            @Override
            public void run() {
                download(url, dir, filename, option, controlor, listener);
            }
        }.start();
    }

    public String downloadEhImage(@NonNull String url, @NonNull File dir,
            @NonNull String filename, @Nullable DownloadOption option,
            @Nullable DownloadControlor controlor,
            @Nullable OnDownloadListener listener) {
        return (String) requst(new DownloadEhImageHelper(url, dir, filename,
                option, controlor, listener));
    }

    public String downloadOriginEhImage(@NonNull String url, @NonNull File dir,
            @NonNull String filename, @Nullable DownloadOption option,
            @Nullable DownloadControlor controlor,
            @Nullable OnDownloadListener listener) {
        return (String) requst(new DownloadOriginEhImageHelper(url, dir, filename,
                option, controlor, listener));
    }

    /** Exceptions **/

    public class SadPandaException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    public class GetBodyException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    public class RedirectionException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    public class UncompletedException extends Exception {

        public UncompletedException(String string) {
            super(string);
        }

        private static final long serialVersionUID = 1L;
    }

    public class ResponseCodeException extends Exception {

        private static final long serialVersionUID = 1L;
        private static final String eMsg = "Error response code";
        private final int mResponseCode;

        public ResponseCodeException(int responseCode) {
            this(responseCode, eMsg);
        }

        public ResponseCodeException(int responseCode, String message) {
            super(message);
            mResponseCode = responseCode;
        }

        @Override
        public String getMessage() {
            return eMsg + ": " + mResponseCode;
        }

        public int getResponseCode() {
            return mResponseCode;
        }
    }

    public class BandwidthExceededException extends Exception {

        private static final long serialVersionUID = 1L;

    }
}
