package net.aeris.aersensor;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity implements SensorEventListener {

    Button startstopbutton;
    
    private SensorManager sensorManager;
    private LocationManager locationManager;
    private TelephonyManager telephonyManager;
    private WifiManager wifiManager;
    private LocationListener gpsListener = new GpsLocationListener();
    private LocationListener networkListener = new NetworkLocationListener();
    private CellInfoListener cellInfoListener = new CellInfoListener();

    private SignalStrength mostRecentSignalStrength;
    private CellIdentityCdma recentCellIdCdma;
    private CellIdentityGsm recentCellIdGsm;
    private CellIdentityLte recentCellIdLte;
    private CellSignalStrengthCdma recentSignalStrengthCdma;
    private CellSignalStrengthGsm recentSignalStrengthGsm;
    private CellSignalStrengthLte recentSignalStrengthLte;
    private Location mostRecentLocation;
    private Context context;
    
    // Temporary sensor measurement holding area
    // TODO a better way?
    URLSettings settings = null;
    DataJson dataJson = null;
    UiHandler uiHandler = null;
    LongPollHandler longPollHandler;
    HandlerThread longPollThread;
    IntentFilter batteryInfoFilter;

    HandlerThread dataUploadThread;
    DataUploadHandler dataUploadHandler;
    
    // FIXME Should add sampling period.
    // FIXME Acquire a WakeLock to prevent device sleep during sampling period.

    // Interval between each sampling period.
    int pollingRate = 3000; // milliseconds

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
    	
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        this.context =  getApplicationContext();
        startstopbutton = (Button) findViewById(R.id.start_stop_button);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        batteryInfoFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(MainActivity.this);
        String temp = preferences.getString("deviceID", "");
        if (temp.equals("Device ID") || temp.equals("")) {
            String dirNum = telephonyManager.getLine1Number();
            if (dirNum != null) {
                Editor editor = preferences.edit();
                editor.putString("deviceID", dirNum);
                editor.commit();
            }
        }

        setSettings();

        // Start button functionality and interaction with other UI
        startstopbutton.setOnClickListener(new View.OnClickListener() {
            public void onClick(final View v) {
                if (("START").equals(startstopbutton.getText())) {
                    // Only register location listerner once in start because
                    // location may take longer than polling interval to obtain.
                    // Therefore, we listen to location changes for the entire
                    // duration of data collection.
                    registerLocationListeners();
                    uiHandler.sendMessage(uiHandler.obtainMessage(UiHandler.START_SAMPLE));
                    setStopButtonState();
                    displayStatus("Initializing");
                } else {
                    uiHandler.sendMessage(uiHandler.obtainMessage(UiHandler.STOP_SAMPLE));
                    setStartButtonState();
                    displayStatus("Stopped");
                }
            }
        });

        uiHandler = new UiHandler(this);
        
        // Start long poll thread
        longPollThread = new HandlerThread("LongPollThread");
        longPollThread.start();
        longPollHandler = new LongPollHandler(longPollThread.getLooper(), uiHandler, context);
        
        // Start data upload thread
        dataUploadThread = new HandlerThread("DataUploadThread");
        dataUploadThread.start();
        dataUploadHandler = new DataUploadHandler(dataUploadThread.getLooper(), uiHandler, context);
    }

    // **************** Application State Handling ******************
    // **************************************************************

    void setStartButtonState() {
        startstopbutton.setText(R.string.start);
        startstopbutton.setBackgroundResource(R.drawable.default_button_gradient);
    }
    
    void setStopButtonState() {
        startstopbutton.setText(R.string.stop);
        startstopbutton.setBackgroundResource(R.drawable.default_stop_gradient);
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    protected void onResume() {
        super.onResume();

        setStartButtonState();
        setSettings();
        startLongPollLoop();
        
        displayStatus("Ready");
    }
    
    private void setSettings() {
        if (settings == null) {
            settings = new URLSettings();
        }
        
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        settings.host = preferences.getString("host", "api.aercloud.aeris.com");
        settings.isHttps = preferences.getBoolean("secure", true);
        
        if (settings.isHttps) {
        	settings.port ="443";
        } else {
        	settings.port = preferences.getString("port", "80");
        }
        
        settings.baseURL = preferences.getString("baseURL", "v1");
        settings.debug = preferences.getBoolean("debug", true);
        settings.accountID = preferences.getString("accountID", "1");
        settings.deviceID = preferences.getString("deviceID", "scl-id");
        settings.containerId = preferences.getString("feedID", "container-id");
        settings.apiKey = preferences.getString("apiKey", "3965e581-120d-11e2-8fb3-6362753ec2a5");
        settings.lphost = preferences.getString("lphost", "longpoll.aercloud.aeris.com");
        settings.lpport = preferences.getString("lpport", "80");
    }

    @Override
    protected void onPause() {
        super.onPause();

        longPollHandler.sendMessage(longPollHandler
                .obtainMessage(LongPollHandler.STOP_HANDLER));
        uiHandler.sendMessage(uiHandler.obtainMessage(UiHandler.STOP_SAMPLE));
    }
    
   private void startLongPollLoop() {
        try {
            longPollHandler.sendMessage(longPollHandler.obtainMessage(
                    LongPollHandler.LONGPOLL_WITH_NEW_URL, settings.makeCmdLongPollUrl()));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        
    }
    
    private void stopDataUploadHandler() {
        // Thread-safe way to stop handlers but does not eliminate race
        // conditions.
        dataUploadHandler.sendMessage(dataUploadHandler
                .obtainMessage(DataUploadHandler.STOP_HANDLER));
    }
    private void unRegisterAllSensors() {
        unRegisterSensors();
        unregisterTelephonyListeners();
        unRegisterLocationListeners();
    }

    protected void OnActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d("In OnActivityResult", "In On Activity Result");
        if (requestCode == 100) {
            if (resultCode == RESULT_OK) {
                setSettings();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem buttonSettings = menu.add("Settings");
        buttonSettings.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {

            public boolean onMenuItemClick(MenuItem item) {
                Class<? extends Activity> c = Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB ? Preferences.class
                        : PreferencesNewerThan11.class;
                Intent settingsIntent = new Intent(MainActivity.this, c);
                startActivityForResult(settingsIntent, 100);
                return false;
            }
        });
        return (super.onCreateOptionsMenu(menu));
    }

    // ******************* UI related functions **********************

    // Enables Start Button and display correct graphics

    // Updates the status text
    private void displayStatus(final String status) {
        final TextView statustext = (TextView) findViewById(R.id.statustext);
        statustext.setText(status);
    }
    private void displayLongPollNotification(final String notification) {
        final TextView longpolltext = (TextView) findViewById(R.id.longpolltext);
        longpolltext.setText(new StringBuffer(notification)
                                 .append(" (@ ").append(SystemClock.uptimeMillis())
                                 .append(")").toString());
    }
    

    private void printDebug(final String debugStr) {
        TextView dataTxt = (TextView) findViewById(R.id.datatext);
        if (debugStr != null) {
            dataTxt.setText(debugStr);
        } else {
            dataTxt.setText("");
        }
    }

    /**
     * Callback that uses the UI thread message queue.
     */
    static class UiHandler extends Handler {
        // Handler can't be non-static inner class otherwise memory
        // leak will occur.
        private final WeakReference<MainActivity> mainActivityRef;
        
        static final int START_SAMPLE = 0;
        static final int STOP_SAMPLE = 1;
        static final int START_UPLOAD = 2;
        static final int LONGPOLLNOTIFICATION = 4;
        static final int BACKGROUND_UPLOAD_COMPLETE = 6;
        
        boolean isCollectingData = false;
        
        public UiHandler(final MainActivity mainActivity) {
            this.mainActivityRef = new WeakReference<MainActivity>(mainActivity);
        }
        
        @Override
        public void handleMessage(final Message msg) {
        	
            MainActivity mainActivity = mainActivityRef.get();
            if (mainActivity == null) {
                Log.e(Constants.LOG_ID, "We are already dead!");
                return;
            }
            
            switch (msg.what) {
            case START_SAMPLE:
                // Don't register for location listener here!
                mainActivity.registerSensors();
                mainActivity.BatteryInfo();
                mainActivity.locationToJson();
                mainActivity.signalStrengthToJson();
                mainActivity.neighboringInfo();
                mainActivity.wifiInfoToJson();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    mainActivity.cdmaInfoToJson();
                    mainActivity.gsmInfoToJson();
                    mainActivity.lteInfoToJson();
                }
                
                setDataCollectionInProgressState();
                this.sendMessageDelayed(
                        this.obtainMessage(UiHandler.START_UPLOAD), mainActivity.pollingRate);
                break;

            case STOP_SAMPLE:
                // Location listener registration/deregistration is asymmetric!
                mainActivity.unRegisterAllSensors();
                mainActivity.stopDataUploadHandler();
                setDataCollectionStoppedState();
                removeMessages(START_SAMPLE);
                removeMessages(START_UPLOAD);
                break;

            case START_UPLOAD:
                if (isDataCollectionInProgress()) {
                    if (mainActivity.dataJson == null) {
                        Log.e(Constants.LOG_ID, "dataJson shouldn't be null");
                        mainActivity.printDebug("");
                    } else {
                        mainActivity.dataJson.put("sampleTime", System.currentTimeMillis());
                        if (mainActivity.settings.debug == true) {
                            mainActivity.printDebug(mainActivity.dataJson.toDbgString());
                        }
    
                        // Send samples to aercloud.
                        // FIXME Only make URL once.
                        try {
                            URL url = new URL(mainActivity.settings.makeFeedUrlString());
                            mainActivity.displayStatus("Uploading to aercloud...");
                            mainActivity.dataUploadHandler.sendMessage(
                                    mainActivity.dataUploadHandler.obtainMessage(
                                            DataUploadHandler.START_UPLOAD_WITH_NEW_URL,
                                            new DataUploadHandler.DataInfo(
                                                    url, mainActivity.settings.isHttps,
                                                    mainActivity.dataJson.toString())));
                        } catch (MalformedURLException e) {
                            Log.e(Constants.LOG_ID, "Incorrect feed posting url");
                        } finally {
                            // Unregister all sensors. Will start again when uploading succeeds.
                            mainActivity.unRegisterAllSensors();
                        }
                    }
                }
                
                break;
                
            case BACKGROUND_UPLOAD_COMPLETE:
                StringBuilder sb = new StringBuilder("Uploading to aercloud...");
                if (msg.arg1 == 200) {
                    sb.append("OK");
                    
                    // Only try again if we are still collecting data
                    if (isCollectingData) {
                        this.sendMessage(this.obtainMessage(UiHandler.START_SAMPLE));
                    }
                } else {
                    // The background thread should be retrying automatically
                    sb.append("Error ").append(msg.arg1).append("...Retrying...");
                }
                mainActivity.displayStatus(sb.toString());
                break;
            case LONGPOLLNOTIFICATION:
            	
                if (msg.obj == null) {
                    Log.d(Constants.LOG_ID, "Programming error: missing cmd str");
                } else {
                	
                    LongPollHandler.Status lpStatus = (LongPollHandler.Status) msg.obj;
                    
                    if (lpStatus.rtCode == 200) {
                        if (lpStatus.cmd != null && lpStatus.cmd.length() > 0) {
                            mainActivity.displayLongPollNotification(lpStatus.cmd);
                            performCommand(mainActivity, lpStatus.cmd);
                        } else {
                            mainActivity.displayLongPollNotification(" ");
                        }
                    } else {
                        mainActivity.displayLongPollNotification(
                                new StringBuffer("Error: ")
                                    .append(lpStatus.rtCode)
                                    .append("...retrying...").toString());
                    }
                    
                }
                    
                break;

            default: // Ignore unknown message
            }
        }
        
        private void performCommand(final MainActivity mainActivity, final String cmd) {
            if(cmd.equals("START_SAMPLING")) {
                if (!isDataCollectionInProgress()) {
                    mainActivity.startstopbutton.performClick();
                }
                
            } else if(cmd.equals("STOP_SAMPLING")) {
                if (isDataCollectionInProgress()) { 
                    mainActivity.startstopbutton.performClick();
                }
            }            
        }
        
        private synchronized void setDataCollectionInProgressState() {
            isCollectingData = true;
        }
        
        private synchronized void setDataCollectionStoppedState() {
            isCollectingData = false;
        }
        
        private synchronized boolean isDataCollectionInProgress() {
            return isCollectingData;
        }
    }
    
    // ************ Sensor Specific Related Functions **************

    // Sensor reading implementation

    /*
     * onSensorChanged look to see if the sensor value has changed and will try
     * to update the data fields to give a current reading.
     */
    public void onSensorChanged(SensorEvent event) {
        Log.d(Constants.LOG_ID, String.valueOf(event.sensor.getType()));
        if (dataJson == null) {
            return;
        }

        // Taking one sample at a time
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            dataJson.put("accelerometerValueX", getAccelerometerValueX(event));
            dataJson.put("accelerometerValueY", getAccelerometerValueX(event));
            dataJson.put("accelerometerValueZ", getAccelerometerValueX(event));
            sensorManager.unregisterListener(this, event.sensor);
        } else if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            dataJson.put("light", getLightValue(event));
            sensorManager.unregisterListener(this, event.sensor);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            dataJson.put("magneticFieldValueX", getMagneticFieldValueX(event));
            dataJson.put("magneticFieldValueY", getMagneticFieldValueX(event));
            dataJson.put("magneticFieldValueZ", getMagneticFieldValueX(event));
            sensorManager.unregisterListener(this, event.sensor);
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            dataJson.put("proximity", getProximityValue(event));
            sensorManager.unregisterListener(this, event.sensor);
        }
        // FIXME proximity sensor may not raise event during sampling interval
    }

    // registers Sensors
    private void registerSensors() {
        dataJson = new DataJson();

        // TODO Sample rate should be configurable but anything other
        // than the default doesn't work.
        // int sampleRate = 30000000; // In microseconds
        int sampleRate = SensorManager.SENSOR_DELAY_NORMAL; // In microseconds

        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        // Only register what we are interested.
        for (Sensor sensor : sensors) {
            switch (sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), sampleRate);
                break;
            case Sensor.TYPE_LIGHT:
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), sampleRate);
                break;
            case Sensor.TYPE_PROXIMITY:
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY), sampleRate);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), sampleRate);
                break;
            default: // do nothing
            }
        }
    }

    // Unregisters all Sensors
    private void unRegisterSensors() {
        sensorManager.unregisterListener(this);
        // printDebug("Unregistered sensors");
        dataJson = null; // Nullify the holding area.
    }

    // Gets the X axis value
    private float getAccelerometerValueX(final SensorEvent event) {
        // value[0] is X axis
        return event.values[0];
    }
    
    // Light sensor data value
    private float getLightValue(final SensorEvent event) {
        return event.values[0];
    }

    // Magnetic Field sensor data: X
    private float getMagneticFieldValueX(final SensorEvent event) {
        return event.values[0];
    }
    
    // Proximity sensor data
    private float getProximityValue(final SensorEvent event) {
        return event.values[0];
    }

    // ************ Location Specific Functions *********************
    private void registerLocationListeners() {
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        boolean enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!enabled) {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }
        Criteria gpsLocation = new Criteria();
        Criteria networkLocation = new Criteria();
        gpsLocation.setAccuracy(Criteria.ACCURACY_FINE);
        networkLocation.setAccuracy(Criteria.ACCURACY_COARSE);
        if (LocationManager.GPS_PROVIDER != null) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0,
                    gpsListener);
        }
        if (LocationManager.NETWORK_PROVIDER != null) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0,
                    networkListener);
        }
    }
    
    private void unRegisterLocationListeners() {
        if (locationManager != null) {
            locationManager.removeUpdates(gpsListener);
            locationManager.removeUpdates(networkListener);
        }
    }

    public class GpsLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            if (isBetterLocation(location, mostRecentLocation) == true) {
                mostRecentLocation = location;
            }
        }

        @Override
        public void onProviderDisabled(String Provider) {

        }

        @Override
        public void onProviderEnabled(String Provider) {

        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle Extras) {

        }

    }

    public class NetworkLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            if (isBetterLocation(location, mostRecentLocation) == true) {
                mostRecentLocation = location;
            }
        }

        @Override
        public void onProviderDisabled(String Provider) {
            // TODO
        }

        @Override
        public void onProviderEnabled(String Provider) {
            // TODO
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle Extras) {
            // TODO
        }

    }

    private boolean isBetterLocation(Location location, Location mostRecentLocation) {
        if (mostRecentLocation == null) {
            return true;
        }
        long timeDelta = location.getTime() - mostRecentLocation.getTime();
        boolean extremelyNew = timeDelta > 120000;
        boolean extremelyOld = timeDelta > -120000;
        boolean newer = timeDelta > 0;

        if (extremelyNew) {
            return true;
        } else if (extremelyOld) {
            return false;
        } else {
            int accuracyDelta = (int) (location.getAccuracy() - mostRecentLocation.getAccuracy());
            boolean lessAccurate = accuracyDelta > 0;
            boolean moreAccurate = accuracyDelta < 0;
            boolean fromSameProvider = isSameProvider(location.getProvider(),
                    mostRecentLocation.getProvider());

            if (moreAccurate) {
                return true;
            } else if (newer && !lessAccurate) {
                return true;
            } else if (newer && !extremelyOld && fromSameProvider) {
                return true;
            } else {
                return false;
            }
        }
    }

    private boolean isSameProvider(String providerLocation, String providerCurrentLocation) {
        if (providerLocation == null) {
            return providerCurrentLocation == null;
        }
        return providerLocation.equals(providerCurrentLocation);
    }

    public void locationToJson() {
        if (mostRecentLocation != null) {
            dataJson.put("Latitude", mostRecentLocation.getLatitude());
            dataJson.put("Longitude", mostRecentLocation.getLongitude());
            dataJson.put("LocationTimeStamp", mostRecentLocation.getTime());

            if (mostRecentLocation.hasAccuracy()) {
                dataJson.put("Accuracy", mostRecentLocation.getAccuracy());
            }

            if (mostRecentLocation.hasAltitude()) {
                dataJson.put("Altitude", mostRecentLocation.getAltitude());
            }

            if (mostRecentLocation.hasBearing()) {
                dataJson.put("Bearing", mostRecentLocation.getBearing());
            }

            if (mostRecentLocation.hasSpeed()) {
                dataJson.put("Speed", mostRecentLocation.getSpeed());
            }
        } else {
            Log.d("NoLocation", "No Location");
        }
    }

    // ************ Battery Information Functions *******************
    private void BatteryInfo() {
        Intent batteryStatus = this.registerReceiver(null, batteryInfoFilter);

        if (batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0x00) != 0x00) {
            dataJson.put("BatteryLevel",
                    batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0x00));
        }

        if (batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, 0x00) != 0x00) {
            dataJson.put("BatteryHealth",
                    batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, 0x00));
        }

        if (batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0x00) != 0x00) {
            dataJson.put("BatteryPlugged",
                    batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0x00));
        }

        if (batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 0x00) != 0x00) {
            dataJson.put("BatteryScale",
                    batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 0x00));
        }

        if (batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, 0x00) != 0x00) {
            dataJson.put("BatteryStatus",
                    batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, 0x00));
        }

        if (batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0x00) != 0x00) {
            dataJson.put("BatteryTemperature",
                    batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0x00));
        }

        if (batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0x00) != 0x00) {
            dataJson.put("BatteryVoltage",
                    batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0x00));
        }

        dataJson.put("BatteryPresent",
                batteryStatus.getExtras().getBoolean(BatteryManager.EXTRA_PRESENT));
        dataJson.put("BatteryType",
                batteryStatus.getExtras().getString(BatteryManager.EXTRA_TECHNOLOGY));
    }

    // ************ Network Information Functions *******************
    public class CellInfoListener extends PhoneStateListener {

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            super.onSignalStrengthsChanged(signalStrength);
            if (signalStrength != null) {
                mostRecentSignalStrength = signalStrength;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    CellularInfo();
                }
                //unregisterTelephonyListeners();
            }
            unregisterTelephonyListeners(); // unregister after one sample.
        }
    }

    public void registerTelephonyListeners() {
        telephonyManager.listen(cellInfoListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
    }

    public void unregisterTelephonyListeners() {
        telephonyManager.listen(cellInfoListener, PhoneStateListener.LISTEN_NONE);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public void CellularInfo() {

        List<CellInfo> allInfo = null;
        allInfo = telephonyManager.getAllCellInfo();
        if (allInfo != null) {
            for (int i = 0; i < allInfo.size(); i++) {
                if (allInfo.get(i) instanceof CellInfoCdma) {
                    CellInfoCdma cellInfoCdma = (CellInfoCdma) allInfo.get(i);
                    recentCellIdCdma = cellInfoCdma.getCellIdentity();
                    recentSignalStrengthCdma = cellInfoCdma.getCellSignalStrength();
                } else if (allInfo.get(i) instanceof CellInfoGsm) {
                    CellInfoGsm cellInfoGsm = (CellInfoGsm) allInfo.get(i);
                    recentCellIdGsm = cellInfoGsm.getCellIdentity();
                    recentSignalStrengthGsm = cellInfoGsm.getCellSignalStrength();
                } else if (allInfo.get(i) instanceof CellInfoLte) {
                    CellInfoLte cellInfoLte = (CellInfoLte) allInfo.get(i);
                    recentCellIdLte = cellInfoLte.getCellIdentity();
                    recentSignalStrengthLte = cellInfoLte.getCellSignalStrength();
                } else {
                    Log.d("CellInfo", "returned a CellInfo");
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void cdmaInfoToJson() {
        CellularInfo();
        if (recentCellIdCdma != null) {
            dataJson.put("CDMABasestationId", recentCellIdCdma.getBasestationId());
            dataJson.put("CDMANetworkId", recentCellIdCdma.getNetworkId());
            dataJson.put("CDMASystemId", recentCellIdCdma.getSystemId());
        }

        if (recentSignalStrengthCdma != null) {
            dataJson.put("CDMAAsuLevel", recentSignalStrengthCdma.getAsuLevel());
            dataJson.put("CDMARssiDbm", recentSignalStrengthCdma.getCdmaDbm());
            dataJson.put("CDMAEcio", recentSignalStrengthCdma.getCdmaEcio());
            dataJson.put("CDMALevel", recentSignalStrengthCdma.getCdmaLevel());
            dataJson.put("CDMDbm", recentSignalStrengthCdma.getDbm());
            dataJson.put("EvdoDbm", recentSignalStrengthCdma.getEvdoDbm());
            dataJson.put("EvdoEcio", recentSignalStrengthCdma.getEvdoEcio());
            dataJson.put("EvdoLevel", recentSignalStrengthCdma.getEvdoLevel());
            dataJson.put("EvdoSnr", recentSignalStrengthCdma.getEvdoSnr());
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void gsmInfoToJson() {
        CellularInfo();
        if (recentCellIdGsm != null) {
            dataJson.put("GsmCid", recentCellIdGsm.getCid());
            dataJson.put("GsmLac", recentCellIdGsm.getLac());
            dataJson.put("GsmMcc", recentCellIdGsm.getMcc());
            dataJson.put("GsmMnc", recentCellIdGsm.getMnc());
            dataJson.put("GsmPsc", recentCellIdGsm.getPsc());
        }

        if (recentSignalStrengthGsm != null) {
            dataJson.put("GsmAsuLevel", recentSignalStrengthGsm.getAsuLevel());
            dataJson.put("GsmDbm", recentSignalStrengthGsm.getDbm());
            dataJson.put("Gsmlevel", recentSignalStrengthGsm.getLevel());
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void lteInfoToJson() {
        CellularInfo();
        if (recentCellIdLte != null) {
            dataJson.put("LteCi", recentCellIdLte.getCi());
            dataJson.put("LteMcc", recentCellIdLte.getMcc());
            dataJson.put("LteMnc", recentCellIdLte.getMnc());
            dataJson.put("LtePci", recentCellIdLte.getPci());
            dataJson.put("LteTac", recentCellIdLte.getTac());
        }

        if (recentSignalStrengthLte != null) {
            dataJson.put("LteAsuLevel", recentSignalStrengthLte.getAsuLevel());
            dataJson.put("LteDdm", recentSignalStrengthLte.getDbm());
            dataJson.put("LteLevel", recentSignalStrengthLte.getLevel());
            dataJson.put("LteTimingAdvance", recentSignalStrengthLte.getTimingAdvance());
        }
    }

    private void signalStrengthToJson() {
        if (mostRecentSignalStrength != null) {
            if (mostRecentSignalStrength.isGsm()) {
                dataJson.put("GSMBitErrorRate", mostRecentSignalStrength.getGsmBitErrorRate());
                dataJson.put("GSMSignalStrength", mostRecentSignalStrength.getGsmSignalStrength());
            } else {
                dataJson.put("CDMACdmaDbm", mostRecentSignalStrength.getCdmaDbm());
                dataJson.put("CDMACdmaEcio", mostRecentSignalStrength.getCdmaEcio());
                dataJson.put("CDMAEvdoDbm", mostRecentSignalStrength.getEvdoDbm());
                dataJson.put("CDMAEvdoEcio", mostRecentSignalStrength.getEvdoEcio());
                dataJson.put("CDMAEvdoSnr", mostRecentSignalStrength.getEvdoSnr());
            }
        }
    }

    private void neighboringInfo() {
        List<NeighboringCellInfo> neighboringCellInfo = null;
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        neighboringCellInfo = telephonyManager.getNeighboringCellInfo();
        if (neighboringCellInfo != null) {
            for (int i = 0; i < neighboringCellInfo.size(); i++) {
                NeighboringCellInfo thisCell = neighboringCellInfo.get(i);
                dataJson.put("NeighborCid" + i, thisCell.getCid());
                dataJson.put("NeighborLac" + i, thisCell.getLac());
                dataJson.put("NeighborNetworkType" + i, thisCell.getNetworkType());
                dataJson.put("NeighborPsc" + i, thisCell.getPsc());
                dataJson.put("NeighborRssi" + i, thisCell.getRssi());
            }
        }
    }

    // ************ Wifi Information Related Functions **************

    void wifiInfoToJson() {
        if (wifiManager.isWifiEnabled()) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                dataJson.put("WifiBSSID", wifiInfo.getBSSID());
                dataJson.put("WifiHiddenSSID", wifiInfo.getHiddenSSID());
                dataJson.put("WifiIpAddress", wifiInfo.getIpAddress());
                dataJson.put("WifiLinkSpeed", wifiInfo.getLinkSpeed());
                dataJson.put("WifiMacAddress", wifiInfo.getMacAddress());
                dataJson.put("WifiNetworkId", wifiInfo.getNetworkId());
                dataJson.put("WifiRssi", wifiInfo.getRssi());
                dataJson.put("WifiSSID", wifiInfo.getSSID());
            }
            dataJson.put("WifiState", wifiManager.getWifiState());
        }
    }

}
