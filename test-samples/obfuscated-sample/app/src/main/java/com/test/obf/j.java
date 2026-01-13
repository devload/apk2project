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

    public static j j() {
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

    public void d(String notificationId, d.i callback) {
        // markNotificationRead
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String m = "/notifications/" + notificationId + "/read";
        c.getInstanceOrCreate(m, "", new d.i() {

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

    private String b(String json, String key) {
        // parseJsonKey
        String n = "\"" + key + "\":\"";
        int o = json.indexOf(n);
        if (o < 0) {
            return null;
        }
        int p = o + n.length();
        int q = json.indexOf("\"", p);
        if (q < 0) {
            return null;
        }
        return json.substring(p, q);
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
