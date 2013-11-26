package net.aeris.aersensor;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class PreferencesNewerThan11 extends Activity  {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("PreferencesNewerThan11", "Starting Preferences");
        getFragmentManager().beginTransaction().replace(android.R.id.content, new PrefFragment())
                .commit();

    }
}