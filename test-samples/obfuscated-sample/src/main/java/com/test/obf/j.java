package com.test.obf;

import java.util.HashMap;
import java.util.Map;

/**
 * 난독화된 ApiService
 * 실제 이름: ApiService
 */
public class j {

    private static j j;
    private c c;  // NetworkClient
    private e e;  // AuthService
    private String k;  // baseUrl

    private j() {
        c = c.c();
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
        String l = e.c();  // getToken()
        if (l == null) {
            callback.b("Not authenticated");
            return;
        }

        String m = "/users/" + userId;
        c.a(m, new d.i() {
            @Override
            public void a(String response) {
                // onSuccess
                String n = b(response, "data");
                callback.a(n);
            }

            @Override
            public void b(String error) {
                // onError
                callback.b(error);
            }
        });
    }

    public void b(Map<String, String> data, d.i callback) {
        // updateUserProfile
        String l = e.c();  // getToken()
        if (l == null) {
            callback.b("Not authenticated");
            return;
        }

        String m = "/users/profile";
        String n = d.b(data.toString());  // base64
        c.c(m, n, new d.i() {
            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.b(error);
            }
        });
    }

    public void c(d.i callback) {
        // getNotifications
        String l = e.c();  // getToken()
        if (l == null) {
            callback.b("Not authenticated");
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
                callback.b(error);
            }
        });
    }

    public void d(String notificationId, d.i callback) {
        // markNotificationRead
        String l = e.c();  // getToken()
        if (l == null) {
            callback.b("Not authenticated");
            return;
        }

        String m = "/notifications/" + notificationId + "/read";
        c.c(m, "", new d.i() {
            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.b(error);
            }
        });
    }

    public void e(String query, int page, d.i callback) {
        // search
        String l = e.c();  // getToken()
        if (l == null) {
            callback.b("Not authenticated");
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
                callback.b(error);
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

        c.c("/auth/register", r.toString(), new d.i() {
            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.b(error);
            }
        });
    }

    public void g(String oldPassword, String newPassword, d.i callback) {
        // changePassword
        String l = e.c();  // getToken()
        if (l == null) {
            callback.b("Not authenticated");
            return;
        }

        Map<String, String> s = new HashMap<>();
        s.put("old_password", oldPassword);
        s.put("new_password", newPassword);

        c.c("/auth/change-password", s.toString(), new d.i() {
            @Override
            public void a(String response) {
                // onSuccess
                callback.a(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.b(error);
            }
        });
    }
}
