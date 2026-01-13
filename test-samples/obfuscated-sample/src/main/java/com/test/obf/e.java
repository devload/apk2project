package com.test.obf;

import java.util.HashMap;
import java.util.Map;

/**
 * 난독화된 AuthService
 * 실제 이름: AuthService
 */
public class e {

    private static e e;
    private b b;  // DataManager
    private c c;  // NetworkClient
    private String f;  // token
    private long g;  // tokenExpiry

    private e() {
        b = b.b();
        c = c.c();
        f = null;
        g = 0;
    }

    public static e e() {
        if (e == null) {
            e = new e();
        }
        return e;
    }

    public boolean a(String username, String password) {
        // login
        if (!d.i(username)) {
            return false;
        }

        String h = b.e("hashed_pwd");
        String i = d.a(password);  // md5

        Map<String, String> j = new HashMap<>();
        j.put("username", username);
        j.put("password", i);

        c.c("/auth/login", j.toString(), new d.i() {
            @Override
            public void a(String response) {
                // onSuccess
                f = k(response, "token");
                long l = d.j(k(response, "expires_in"));
                g = System.currentTimeMillis() + l * 1000;
                b.a("auth_token", f);
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
        b.f();  // clearCache
    }

    public String c() {
        // getToken
        if (f == null) {
            f = b.e("auth_token");
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
        return c() != null;
    }

    private String k(String json, String key) {
        // parseJsonKey
        String l = "\"" + key + "\":\"";
        int m = json.indexOf(l);
        if (m < 0) {
            return null;
        }
        int n = m + l.length();
        int o = json.indexOf("\"", n);
        if (o < 0) {
            return null;
        }
        return json.substring(n, o);
    }
}
