package net.aeris.aersensor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;

import net.aeris.aersensor.MainActivity.UiHandler;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

class DataUploadHandler extends Handler {

    static final int START_UPLOAD_WITH_NEW_URL = 1;
    static final int START_UPLOAD_WITH_CURRENT_URL = 2;
    static final int STOP_HANDLER = 3;

    private final UiHandler uiHandler;
    private boolean isRunning = false;
    private final Context context;
//    private SSLContext sslContext;
    private boolean isHttpsInitialized = false;

    /*
     * Command to create the keystore and add the self signed certificate
     * 
     * keytool -import -alias aersensorcert -file aercloud-beta.crt -keypass
     * aersensorcert -storetype BKS -providerClass
     * org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath
     * aersensor/libs/bcprov-jdk15on-146.jar -keystore
     * aersensor/res/raw/aersensorcert
     */

    static class DataInfo {
        URL url;
        boolean isHttps;
        String jsonStr;

        DataInfo(final URL url, final boolean isHttps, final String jsonStr) {
            this.url = url;
            this.isHttps = isHttps;
            this.jsonStr = jsonStr;
        }
    }

    DataUploadHandler(final Looper looper, final UiHandler uiHandler, final Context context) {
        super(looper);
        this.uiHandler = uiHandler;
        this.context = context;
    }

    private synchronized void initSSL() {
        try {
            if (!isHttpsInitialized) {
//                char[] passphrase = "aersensor".toCharArray();
//                KeyStore ksStoreTrust = KeyStore.getInstance(Constants.BKS_STORE_TYPE);
//                ksStoreTrust.load(context.getResources().openRawResource(R.raw.aersensorcert),
//                        passphrase);
//                TrustManagerFactory tmf = TrustManagerFactory.getInstance(KeyManagerFactory
//                        .getDefaultAlgorithm());
//                tmf.init(ksStoreTrust);
//
//                this.sslContext = SSLContext.getInstance("TLS");
//                sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
                isHttpsInitialized = true;
            }
        } catch (Exception e) {
            String errMsg = e.getMessage(); // Nexus S may return null here.
            Log.e("IOException in DataUploadHandler: ", errMsg != null ? errMsg : "Unknown error.");
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case STOP_HANDLER:
            removeMessages(START_UPLOAD_WITH_CURRENT_URL);
            removeMessages(START_UPLOAD_WITH_NEW_URL);
            setToStopped();
            return;
        case START_UPLOAD_WITH_NEW_URL:
            DataInfo dataInfo = getDataInfoFromMsg(msg);
            if (dataInfo == null) {
                return;
            }

            // Remove any pending requests
            removeMessages(START_UPLOAD_WITH_CURRENT_URL);

            // Start a new one
            setToRunning();
            this.sendMessage(this.obtainMessage(START_UPLOAD_WITH_CURRENT_URL, dataInfo));
            break;

        case START_UPLOAD_WITH_CURRENT_URL:
            if (isRunning()) {
                dataInfo = getDataInfoFromMsg(msg);
                if (dataInfo == null) {
                    return;
                }

                int status = upload(dataInfo);
                if (status != 200) {
                    // Retry in 3 seconds
                    this.sendMessageDelayed(
                            this.obtainMessage(START_UPLOAD_WITH_CURRENT_URL, dataInfo), 3000);
                }
                uiHandler.sendMessage(uiHandler.obtainMessage(
                        MainActivity.UiHandler.BACKGROUND_UPLOAD_COMPLETE, status, 0));
            }
            break;
        default:
            // Ignore unknown messages
        }
    }

    private int upload(final DataInfo dataInfo) {
        Log.d(Constants.LOG_ID, dataInfo.url.toExternalForm());

        HttpURLConnection urlConnection = null;
        OutputStream out = null;
        InputStream errStream = null;

        try {

            if (dataInfo.isHttps) {
                // HTTPS
                initSSL();
                HttpsURLConnection httpsUrlConnection = (HttpsURLConnection) dataInfo.url.openConnection();
//                httpsUrlConnection.setSSLSocketFactory(sslContext.getSocketFactory());
//                // Currently accepting all Hostname
//                httpsUrlConnection.setHostnameVerifier(new DefaultHostNameVerifier());
                urlConnection = httpsUrlConnection;
            } else {
                urlConnection = (HttpURLConnection) dataInfo.url.openConnection();
            }

            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type", "application/json");
            byte[] data = dataInfo.jsonStr.getBytes("UTF-8");

            // urlConnection.setRequestProperty("Content-Length",
            // Integer.toString(data.length));
            // TODO Explicitly set to the streaming mode so the implementation
            // does not need
            // to calculate length.
            urlConnection.setFixedLengthStreamingMode(data.length);

            urlConnection.setRequestProperty("Accept", "application/json");

            // Assuming we don't know content
            // urlConnection.setChunkedStreamingMode(0);

            // Disable gzip.
            // urlConnection.setRequestProperty("Accept-Encoding", "identity");
            urlConnection.setReadTimeout(5000);

            // Send POST
            out = urlConnection.getOutputStream();
            out.write(data);
            out.flush();

            // Get response
            return urlConnection.getResponseCode();
        } catch (Exception e) {
            String errMsg = e.getMessage(); // Nexus S may return null
                                            // here.sssssssssss
            Log.e("IOException in DataUploadHandler: ", errMsg != null ? errMsg : "Unknown error.");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e1) {
                    // ignore
                }
            }

            if (errStream != null) {
                try {
                    errStream.close();
                } catch (Exception e1) {
                    // ignore
                }
            }

            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
        return -1; // Connection problems
    }

    private DataInfo getDataInfoFromMsg(final Message msg) {
        if (msg.obj == null) {
            String err = "Programming error: missing DataInfo";
            Log.e(Constants.LOG_ID, err);
            throw new RuntimeException(err);
        }

        DataInfo dataInfo = (DataInfo) msg.obj;
        if (dataInfo.url == null || dataInfo.jsonStr == null) {
            String err = "Programming error: missing url or jsonStr";
            Log.e(Constants.LOG_ID, err);
            throw new RuntimeException(err);

        } else {
            return dataInfo;
        }
    }

    private synchronized boolean isRunning() {
        return isRunning;
    }

    private synchronized void setToRunning() {
        isRunning = true;
    }

    private synchronized void setToStopped() {
        isRunning = false;

    }
    
    class DefaultHostNameVerifier implements HostnameVerifier {

        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }

    }
}
