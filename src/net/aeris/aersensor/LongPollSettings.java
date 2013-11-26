package net.aeris.aersensor;

public class LongPollSettings {
    String host;
    String port;
    String baseURL;
    String accountID;
    String deviceID;
    
    String makeLongPollURL() {
        StringBuilder sb = new StringBuilder("http://");
        sb.append(host).append(":").append("9090").append("/")
            .append("notificationChannels").append("/")
            .append("mgmtCmd").append("/").append("LP.MGMTCMD")
            .append(".").append(accountID).append(".")
            .append(deviceID);
        return sb.toString();
    }
}