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

    public static g g() {
        if (g == null) {
            g = new g();
        }
        return g;
    }

    // In-memory storage for testing
    private Map<String, String> j = new HashMap<>();

    public Map<String, String> k() {
        // loadPreferences
        return new HashMap<>(j);
    }

    public void a(String key, String value) {
        // savePreference
        j.put(key, value);
    }

    public String b(String key) {
        // getPreference
        return j.get(key);
    }

    public void c(String key) {
        // removePreference
        j.remove(key);
    }

    public void f() {
        // clearPreferences
        j.clear();
    }

    public boolean d(String key) {
        // contains
        return j.containsKey(key);
    }

    public void e(String key, String defaultValue) {
        // putIfAbsent
        if (!j.containsKey(key)) {
            j.put(key, defaultValue);
        }
    }
}
