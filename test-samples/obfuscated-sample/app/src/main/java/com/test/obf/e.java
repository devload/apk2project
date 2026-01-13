package com.test.obf;

import java.util.HashMap;
import java.util.Map;

/**
 * 난독화된 AuthService
 * 실제 이름: AuthService
 */
public class e {

    private static e e;

    // DataManager
    private b b;

    // NetworkClient
    private c c;

    // token
    private String f;

    // tokenExpiry
    private long g;

    private e() {
        b = b.b();
        c = c.getInstanceOrCreate();
        f = null;
        g = 0;
    }

    // [Deobfuscated] e -> getInstance * Provides an instance of the class e, creating it if necessary.
    public static e getInstance() {
        if (e == null) {
            e = new e();
        }
        return e;
    }

    public boolean a(String username, String password) {
        // login
        if (!d.parseInteger(username)) {
            return false;
        }
        String h = b.getData("hashed_pwd");
        // md5
        String i = d.md5HashGenerator(password);
        Map<String, String> j = new HashMap<>();
        j.put("username", username);
        j.put("password", i);
        c.getInstanceOrCreate("/auth/login", j.toString(), new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                f = parseJsonKey(response, "token");
                long l = d.parseLongOrZero(parseJsonKey(response, "expires_in"));
                g = System.currentTimeMillis() + l * 1000;
                b.md5HashGenerator("auth_token", f);
            }

            @Override
            public void b(String error) {
                // onError
                f = null;
                g = 0;
            }
        });
        return f != null;
    }

    public void b() {
        // logout
        f = null;
        g = 0;
        // clearCache
        b.f();
    }

    public String c() {
        // getToken
        if (f == null) {
            f = b.getData("auth_token");
        }
        if (g > 0 && System.currentTimeMillis() > g) {
            // Token expired
            f = null;
            return null;
        }
        return f;
    }

    public boolean d() {
        // isAuthenticated
        return getInstanceOrCreate() != null;
    }

    // [Deobfuscated] k -> parseJsonKey * Parses a JSON string and returns the value of a specific key
    private String parseJsonKey(String inputJson, String searchKey) {
        // parseJsonKey
        String searchString = "\"" + searchKey + "\":\"";
        int startIndex = inputJson.indexOf(searchString);
        if (startIndex < 0) {
            return null;
        }
        int valueStartIndex = startIndex + searchString.length();
        int valueEndIndex = inputJson.indexOf("\"", valueStartIndex);
        if (valueEndIndex < 0) {
            return null;
        }
        return inputJson.substring(valueStartIndex, valueEndIndex);
    }
}
