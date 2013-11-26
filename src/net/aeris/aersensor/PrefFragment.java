package net.aeris.aersensor;

import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class PrefFragment extends PreferenceFragment implements OnSharedPreferenceChangeListener {
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
        SharedPreferences sp = getPreferenceScreen().getSharedPreferences();
        
        EditTextPreference hostSummary = (EditTextPreference) findPreference( "host" );
        hostSummary.setSummary(sp.getString("host", "host"));
        EditTextPreference portSummary = (EditTextPreference) findPreference( "port" );
        portSummary.setSummary(sp.getString("port", "port"));
        EditTextPreference baseURLSummary = (EditTextPreference) findPreference( "baseURL" );
        baseURLSummary.setSummary(sp.getString("baseURL", "base URL"));
        EditTextPreference accountIDSummary = (EditTextPreference) findPreference( "accountID" );
        accountIDSummary.setSummary(sp.getString("accountID", "Account ID"));
        EditTextPreference deviceIDSummary = (EditTextPreference) findPreference( "deviceID" );
        deviceIDSummary.setSummary(sp.getString("deviceID", "Device ID"));
        EditTextPreference feedIDSummary = (EditTextPreference) findPreference( "feedID" );
        feedIDSummary.setSummary(sp.getString("feedID", "Feed ID"));
        EditTextPreference apiKeySummary = (EditTextPreference) findPreference( "apiKey" );
        apiKeySummary.setSummary(sp.getString("apiKey", "API Key"));
        
        EditTextPreference lphostSummary = (EditTextPreference) findPreference( "lphost" );
        lphostSummary.setSummary(sp.getString("lphost", "host"));
        
        EditTextPreference lpportSummary = (EditTextPreference) findPreference( "lpport" );
        lpportSummary.setSummary(sp.getString("lpport", "port"));
        
        CheckBoxPreference checkBoxPreference = (CheckBoxPreference) findPreference( "secure" );
        boolean checkbox_secure = sp.getBoolean("secure", true);
        checkBoxPreference.setChecked(checkbox_secure);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
                this);
    }
    
    public void onSharedPreferenceChanged(SharedPreferences 
            sharedPreferences,String key) {
        Preference pref = findPreference(key);
        if (pref instanceof EditTextPreference) {
            EditTextPreference etp = (EditTextPreference) pref;
            pref.setSummary(etp.getText());
        }
    }
    

}
