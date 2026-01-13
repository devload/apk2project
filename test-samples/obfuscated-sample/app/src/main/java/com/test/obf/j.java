package com.test.obf;

import java.util.HashMap;
import java.util.Map;

/**
 * 난독화된 ApiService
 * 실제 이름: ApiService
 */
public class j {

    private static j j;

    // NetworkClient
    private c c;

    // AuthService
    private e e;

    // baseUrl
    private String k;

    private j() {
        c = c.getInstanceOrCreate();
        e = e.e();
        k = "https://api.example.com/v1";
    }

    // [Deobfuscated] j -> getInstanceOrCreate * Returns an instance of the class 'j' or creates a new one if it doesn't exist yet.
    public static j getInstanceOrCreate() {
        if (j == null) {
            j = new j();
        }
        return j;
    }

    public void a(String userId, d.i callback) {
        // getUserProfile
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String m = "/users/" + userId;
        c.a(m, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                String n = base64Encode(response, "data");
                callback.a(n);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    public void b(Map<String, String> data, d.i callback) {
        // updateUserProfile
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String m = "/users/profile";
        // base64
        String n = d.base64Encode(data.toString());
        c.getInstanceOrCreate(m, n, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    public void c(d.i callback) {
        // getNotifications
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String m = "/notifications";
        c.a(m, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] d -> markNotificationAsRead * Marks a notification as read if the user is authenticated
    public void markNotificationAsRead(String notificationId, d.i responseCallback) {
        // markNotificationRead
        // getToken()
        String token = e.getInstanceOrCreate();
        if (token == null) {
            responseCallback.base64Encode("Not authenticated");
            return;
        }
        String notificationPath = "/notifications/" + notificationId + "/read";
        c.getInstanceOrCreate(notificationPath, "", new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                responseCallback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                responseCallback.base64Encode(error);
            }
        });
    }

    public void e(String query, int page, d.i callback) {
        // search
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String m = "/search?q=" + query + "&page=" + page;
        c.a(m, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] b -> extractJsonValueByKey * Extracts the value associated with a specific key in a JSON string
    private String extractJsonValueByKey(String json, String key) {
        // parseJsonKey
        String jsonKeyWithQuotes = "\"" + key + "\":\"";
        int indexOfJsonKeyStart = json.indexOf(jsonKeyWithQuotes);
        if (indexOfJsonKeyStart < 0) {
            return null;
        }
        int startIndexOfValue = indexOfJsonKeyStart + jsonKeyWithQuotes.length();
        int endIndexOfValue = json.indexOf("\"", startIndexOfValue);
        if (endIndexOfValue < 0) {
            return null;
        }
        return json.substring(startIndexOfValue, endIndexOfValue);
    }

    public void f(String username, String email, String password, d.i callback) {
        // register
        Map<String, String> r = new HashMap<>();
        r.put("username", username);
        r.put("email", email);
        r.put("password", password);
        c.getInstanceOrCreate("/auth/register", r.toString(), new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    public void g(String oldPassword, String newPassword, d.i callback) {
        // changePassword
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        Map<String, String> s = new HashMap<>();
        s.put("old_password", oldPassword);
        s.put("new_password", newPassword);
        c.getInstanceOrCreate("/auth/change-password", s.toString(), new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }
}
