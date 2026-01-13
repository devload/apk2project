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

    // [Deobfuscated] a -> authenticateUser * Authenticates a user with the given username and password
    public boolean authenticateUser(String userName, String passWord) {
        // login
        if (!d.parseInteger(userName)) {
            return false;
        }
        String hashedPasswordFromDB = b.getData("hashed_pwd");
        // md5
        String md5HashedPassword = d.md5HashGenerator(passWord);
        Map<String, String> requestBodyMap = new HashMap<>();
        requestBodyMap.put("username", userName);
        requestBodyMap.put("password", md5HashedPassword);
        c.getInstanceOrCreate("/auth/login", requestBodyMap.toString(), new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                f = parseJsonKey(response, "token");
                long expiryTimeInSeconds = d.parseLongOrZero(parseJsonKey(response, "expires_in"));
                g = System.currentTimeMillis() + expiryTimeInSeconds * 1000;
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
        b.clearCacheAndPreferences();
    }

    // [Deobfuscated] c -> getAuthToken * Retrieves and manages the authentication token
    public String getAuthToken() {
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

    // [Deobfuscated] d -> isAuthenticated * checks if the instance exists and is not null
    public boolean isAuthenticated() {
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
