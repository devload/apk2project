package com.test.obf;

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
    private f f;  // DatabaseHelper
    private g g;  // PreferenceManager

    private b() {
        c = new ConcurrentHashMap<>();
        f = f.f();
        g = g.g();
        j();
    }

    public static b b() {
        if (b == null) {
            synchronized (b.class) {
                if (b == null) {
                    b = new b();
                }
            }
        }
        return b;
    }

    private void j() {
        // initialize
        Map<String, String> k = g.k();  // loadPreferences()
        if (k != null) {
            c.putAll(k);
        }

        Map<String, String> l = f.l();  // loadFromDatabase()
        if (l != null) {
            c.putAll(l);
        }
    }

    public Map<String, String> d() {
        // getUserData
        Map<String, String> m = new HashMap<>();
        m.put("user_id", c.get("uid"));
        m.put("user_name", c.get("uname"));
        m.put("user_email", c.get("email"));
        return m;
    }

    public void a(String key, String value) {
        // saveData
        c.put(key, value);
        g.a(key, value);  // savePreference
        f.m(key, value);  // saveToDatabase
    }

    public void c(long timestamp, String action) {
        // logEvent
        String n = String.valueOf(timestamp);
        String o = "event_" + action;
        a(o, n);
    }

    public String e(String key) {
        // getData
        return c.get(key);
    }

    public void f() {
        // clearCache
        c.clear();
        g.f();  // clearPreferences
    }
}
