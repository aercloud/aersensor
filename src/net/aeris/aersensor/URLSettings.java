package net.aeris.aersensor;

import java.net.MalformedURLException;
import java.net.URL;

public class URLSettings{
    String host;
    String port;
    boolean isHttps;
    String baseURL;
    String datatimer;
    String accountID;
    String deviceID;
    String containerId;
    String apiKey;
    String lphost;
    String lpport;
    boolean debug;
    
    String makeFeedUrlString() {
    	String scheme = "http://";
    	if (isHttps) {
    		scheme = "https://";
    	}
    	
        StringBuilder sb = new StringBuilder(scheme);
        sb.append(host).append(":").append(port).append("/")
            .append(baseURL).append("/").append(accountID)
            .append("/").append("scls").append("/")
            .append(deviceID).append("/").append("containers")
            .append("/").append(containerId).append("/")
            .append("contentInstances").append("?apiKey=").append(apiKey);
        return sb.toString();
    }
    
    String makeCmdLongPollUrlString() {
    	String scheme = "http://";
    	// Dafaulting to always use http for now
    	/*if (isHttps) {
    		scheme = "https://";
    	}*/
        StringBuilder sb = new StringBuilder(scheme);
        sb.append(lphost).append(":").append(lpport).append("/v1/")
            .append(accountID)
            .append("/").append("scls").append("/")
            .append(deviceID).append("/").append("notificationChannels")
            .append("/").append("mgmtCmd").append("?apiKey=").append(apiKey);
        return sb.toString();
    }
    
    URL makeCmdLongPollUrl() throws MalformedURLException {
        return new URL(makeCmdLongPollUrlString());
    }
    
    public boolean isHttps() {
    	return isHttps;
    }
}