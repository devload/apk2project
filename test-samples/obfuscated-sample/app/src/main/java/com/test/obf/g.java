package com.test.obf;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/**
 * 난독화된 PreferenceManager
 * 실제 이름: PreferenceManager
 */
public class g {

    private static g g;

    private SharedPreferences h;

    private SharedPreferences.Editor i;

    private g() {
        // Android Context would be injected in real app
        // For test, we use in-memory storage
    }

    // [Deobfuscated] g -> getInstanceOrCreate * Returns an instance of class g or creates a new one if it doesn't exist yet.
    public static g getInstanceOrCreate() {
        if (g == null) {
            g = new g();
        }
        return g;
    }

    // In-memory storage for testing
    private Map<String, String> j = new HashMap<>();

    // [Deobfuscated] k -> loadPreferences * Loads preferences from a HashMap and returns it as a new Map.
    public Map<String, String> loadPreferences() {
        // loadPreferences
        return new HashMap<>(j);
    }

    // [Deobfuscated] a -> savePreference * Saves a preference using the provided key and value.
    public void savePreference(String preferenceKey, String preferenceValue) {
        // savePreference
        j.put(preferenceKey, preferenceValue);
    }

    // [Deobfuscated] b -> getPreference * retrieves a preference value from a map using the provided key
    public String getPreference(String preferenceKey) {
        // getPreference
        return j.get(preferenceKey);
    }

    // [Deobfuscated] c -> getPreference * retrieves a preference value from a map using the provided key
    public void getPreference(String key) {
        // removePreference
        j.remove(key);
    }

    // [Deobfuscated] f -> clearPreferences * clears user preferences
    public void clearPreferences() {
        // clearPreferences
        j.clear();
    }

    // [Deobfuscated] d -> containsKey * checks if a key exists in the map
    public boolean containsKey(String searchKey) {
        // contains
        return j.containsKey(searchKey);
    }

    // [Deobfuscated] e -> initializeMapEntry * initializes a map entry if it does not exist
    public void initializeMapEntry(String mapKey, String defaultMapValue) {
        // putIfAbsent
        if (!j.containsKey(mapKey)) {
            j.put(mapKey, defaultMapValue);
        }
    }
}
