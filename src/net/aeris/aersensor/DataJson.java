package net.aeris.aersensor;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * We use the absence of parameter to indicate if a value is set.
 */
public class DataJson {
    private final JSONObject jsonObj = new JSONObject();
    
    public void put(final String field, final float value) {
        try {
            jsonObj.put(field, value);
        } catch (JSONException e) {
            throw new RuntimeException("Programming error", e);
        }
    }
    
    public void put(final String field, final double value) {
        try {
            jsonObj.put(field, value);
        } catch (JSONException e) {
            throw new RuntimeException("Programming error", e);
        }
    }
    
    public void put(final String field, final int value) {
        try {
            jsonObj.put(field, value);
        } catch (JSONException e) {
            throw new RuntimeException("Programming error", e);
        }
    }
    
    public void put(final String field, final long value) {
        try {
            jsonObj.put(field, value);
        } catch (JSONException e) {
            throw new RuntimeException("Programming error", e);
        }
    }
    
    public void put(final String field, final boolean value) {
        try {
            jsonObj.put(field, value);
        } catch (JSONException e) {
            throw new RuntimeException("Programming error", e);
        }
    }
    
    public void put(final String field, final String value) {
        try {
            jsonObj.put(field, value);
        } catch (JSONException e) {
            throw new RuntimeException("Programming error", e);
        }
    }
    
    @Override
    public String toString() {
        return jsonObj.toString();
    }
    
    public String toDbgString() {
        try {
            return jsonObj.toString(4);
        } catch (JSONException e) {
            throw new RuntimeException("Programming error", e);
        }
    }
}
