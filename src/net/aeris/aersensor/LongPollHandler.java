package net.aeris.aersensor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

class LongPollHandler extends Handler {
	
    static final int LONGPOLL_WITH_NEW_URL = 1;
    static final int LONGPOLL_WITH_EXISINT_URL = 2;
    static final int STOP_HANDLER = 3;
    private final UiHandler uiHandler;
    private boolean isRunning = false;
	private boolean isHttpsInitialized;
//	private SSLContext sslContext;
	private Context context;
    
    public LongPollHandler(final Looper looper, final UiHandler uiHandler, final Context context) {
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
//                this.sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
                this.isHttpsInitialized = true;
            }
        } catch (Exception e) {
            String errMsg = e.getMessage(); // Nexus S may return null here.
            Log.e("IOException in DataUploadHandler: ", errMsg != null ? errMsg : "Unknown error.");
        }
    }

    @Override
    public void handleMessage(final Message msg) {
    	
        switch(msg.what) {
        case STOP_HANDLER:
            removeMessages(LONGPOLL_WITH_EXISINT_URL);
            removeMessages(LONGPOLL_WITH_NEW_URL);
            setToStopped();
            return;
        
        case LONGPOLL_WITH_NEW_URL:
        	
            URL lpUrl = getUrlFromMsg(msg);
            if (lpUrl == null) {
                return;
            }
            
            // Remove any long poll request in the queue because
            // We will start a new one.
            this.removeMessages(LONGPOLL_WITH_EXISINT_URL);
            
            // Now start a new long poll request
            setToRunning();
            this.sendMessage(this.obtainMessage(LONGPOLL_WITH_EXISINT_URL, lpUrl));
            break;
            
        case LONGPOLL_WITH_EXISINT_URL:
        	
            if (isRunning()) {
                lpUrl = getUrlFromMsg(msg);
                if (lpUrl == null) {
                    throw new RuntimeException("lpUrl can't be null!");
                }

                // Execute long polling
                Status status = executeLongPoll(lpUrl);
                if (status.rtCode == 200) {
                    this.sendMessage(this.obtainMessage(LONGPOLL_WITH_EXISINT_URL, lpUrl));
                } else {
                    // We have an error. Wait 3 seconds before long poll again
                    this.sendMessageDelayed(this.obtainMessage(LONGPOLL_WITH_EXISINT_URL, lpUrl), 3000);
                }
                uiHandler.sendMessage(uiHandler.obtainMessage(UiHandler.LONGPOLLNOTIFICATION, status));
            }
            
            break;

        default: // Ignore unknown message
        }
    }
    
    private URL getUrlFromMsg(final Message msg) {
        if (msg.obj == null) {
            Log.e(Constants.LOG_ID, "Programming error: missing url");
            return null;
        }
        return (URL)msg.obj;
    }
    
    private Status executeLongPoll(final URL lpUrl) {
    	
        Log.d(Constants.LOG_ID, lpUrl.toExternalForm());
        HttpURLConnection urlConnection = null;
        InputStream inputStream = null;
        InputStream errStream = null;
        int rtStatus = -1;
        try {
        	boolean isHttps = lpUrl.getProtocol().equalsIgnoreCase("https");
        	if (isHttps) {
                // HTTPS
                initSSL();
                HttpsURLConnection httpsUrlConnection = (HttpsURLConnection) lpUrl.openConnection();
//                httpsUrlConnection.setSSLSocketFactory(sslContext.getSocketFactory());
//                // Currently accepting all Hostname
//                httpsUrlConnection.setHostnameVerifier(new DefaultHostNameVerifier());
                urlConnection = httpsUrlConnection;
            } else {
                urlConnection = (HttpURLConnection) lpUrl.openConnection();
            }
        	
            urlConnection.setDoInput(true);
            urlConnection.setRequestMethod("GET");
            //urlConnection.setRequestProperty("Accept", "application/json");
            // FIXME Use read timeout instead of infinite
            urlConnection.setReadTimeout(0);
            
            urlConnection.connect();
            
            rtStatus = urlConnection.getResponseCode();
            if (rtStatus == 200) {
            	
                int len = urlConnection.getContentLength();
                if (len < 0) {
                    Log.e(Constants.LOG_ID, "Missing content-length in response");
                    return new Status(rtStatus, null);
                    
                } else if (len == 0) {
                    // Server timed out because no command pending
                    return new Status(rtStatus, null);
                }
                
                // Send GET and wait for the response
                inputStream = urlConnection.getInputStream();
                InputStreamReader entityInput = new InputStreamReader(inputStream, "UTF-8");
                char[] cmdChars = new char[len];
                int n = entityInput.read(cmdChars);
                if (n < len) {
                    Log.e(Constants.LOG_ID, 
                            new StringBuffer("Read ")
                                .append(" but expecting ")
                                .append(len).toString());
                    return new Status(rtStatus, null);
                }
                String cmd = new String(cmdChars);
                Log.d(Constants.LOG_ID, "Received command: " + cmd);
                return new Status(rtStatus, cmd);
            } 
            
            return new Status(rtStatus, null);
            
        } catch (Exception e) {
            String errMsg = e.toString();
            Log.e(Constants.LOG_ID, "IOException in LongPollHandler: " + errMsg != null ? errMsg : "Unknown error.");
            return new Status(-1, null);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e1) {
                    // Ignore
                }
            }
            
            if (errStream != null) {
                try {
                    errStream.close();
                } catch (Exception e1) {
                    // Ignore
                }
            }
            
            try {
	            if (urlConnection != null) {
	        		urlConnection.disconnect();
	            }
            } catch (Throwable e) {
            		//Ignore
            }
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
    
    static class Status {
        int rtCode;
        String cmd;
        public Status(final int rtCode, final String cmd) {
            super();
            this.rtCode = rtCode;
            this.cmd = cmd;
        }
    }
    
    class DefaultHostNameVerifier implements HostnameVerifier {

        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }

    }

}
