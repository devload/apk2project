package com.test.obf;

import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 난독화된 DataManager
 * 실제 이름: DataManager
 * 싱글톤 패턴
 */
public class b {

    private static b b;

    private Map<String, String> c;

    // DatabaseHelper
    private f f;

    // PreferenceManager
    private g g;

    private b() {
        c = new ConcurrentHashMap<>();
        f = f.clearPreferences();
        g = g.g();
        j();
    }

    // [Deobfuscated] b -> getInstance * Ensures that only one instance of the class b is created and returns it.
    public static b getInstance() {
        if (b == null) {
            synchronized (b.class) {
                if (b == null) {
                    b = new b();
                }
            }
        }
        return b;
    }

    // [Deobfuscated] j -> loadPreferencesAndDatabaseSettings * Loads preferences and database settings into a map
    private void loadPreferencesAndDatabaseSettings() {
        // initialize
        // loadPreferences()
        Map<String, String> preferencesMap = g.loadPreferences();
        if (preferencesMap != null) {
            c.putAll(preferencesMap);
        }
        // loadFromDatabase()
        Map<String, String> databaseSettingsMap = f.loadPreferencesFromDatabase();
        if (databaseSettingsMap != null) {
            c.putAll(databaseSettingsMap);
        }
    }

    // [Deobfuscated] d -> getUserData * Fetches user data and stores it in a map
    public Map<String, String> getUserData() {
        // getUserData
        Map<String, String> userDataMap = new HashMap<>();
        userDataMap.put("user_id", c.get("uid"));
        userDataMap.put("user_name", c.get("uname"));
        userDataMap.put("user_email", c.get("email"));
        return userDataMap;
    }

    // [Deobfuscated] a -> saveDataToStorageAndDatabase * Saves data to different storage and database systems
    public void saveDataToStorageAndDatabase(String dataKey, String dataValue) {
        // saveData
        c.put(dataKey, dataValue);
        // savePreference
        g.savePreference(dataKey, dataValue);
        // saveToDatabase
        f.saveToDatabase(dataKey, dataValue);
    }

    // [Deobfuscated] c -> logEvent * Logs an event with a timestamp and action name
    public void logEvent(long eventTimestamp, String eventAction) {
        // logEvent
        String n = String.valueOf(eventTimestamp);
        String o = "event_" + eventAction;
        savePreference(o, n);
    }

    // [Deobfuscated] e -> getData * retrieves data from a collection using a key
    public String getData(String dataKey) {
        // getData
        return c.get(dataKey);
    }

    // [Deobfuscated] f -> clearCacheAndPreferences * clears cache and preferences
    public void clearCacheAndPreferences() {
        // clearCache
        c.clear();
        // clearPreferences
        g.clearPreferences();
    }
}
