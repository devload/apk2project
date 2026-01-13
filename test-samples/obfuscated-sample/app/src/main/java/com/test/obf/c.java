package com.test.obf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 난독화된 NetworkClient
 * 실제 이름: NetworkClient
 */
public class c {

    private static c c;

    private ExecutorService d;

    private String e;

    private int f;

    private c() {
        d = Executors.newFixedThreadPool(5);
        e = "https://api.example.com";
        // timeout
        f = 30000;
    }

    // [Deobfuscated] c -> getInstanceOrCreate * Returns an instance of class c, creating it if it doesn't exist yet.
    public static c getInstanceOrCreate() {
        if (c == null) {
            c = new c();
        }
        return c;
    }

    public void a(String path, d.i callback) {
        // get
        d.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    String g = b(path, "GET");
                    if (callback != null) {
                        callback.a(g);
                    }
                } catch (Exception e) {
                    callback.b(e.getMessage());
                }
            }
        });
    }

    public void c(String path, String body, d.i callback) {
        // post
        d.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    String g = h(path, body, "POST");
                    if (callback != null) {
                        callback.a(g);
                    }
                } catch (Exception e) {
                    callback.b(e.getMessage());
                }
            }
        });
    }

    private String b(String path, String method) throws Exception {
        // executeRequest
        URL url = new URL(e + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(f);
        conn.setReadTimeout(f);
        int i = conn.getResponseCode();
        if (i == 200) {
            BufferedReader j = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder k = new StringBuilder();
            String l;
            while ((l = j.readLine()) != null) {
                k.append(l);
            }
            j.close();
            return k.toString();
        } else {
            throw new Exception("HTTP " + i);
        }
    }

    private String h(String path, String body, String method) throws Exception {
        // executeWithBody
        URL url = new URL(e + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(f);
        conn.setReadTimeout(f);
        conn.setDoOutput(true);
        conn.getOutputStream().write(body.getBytes());
        int i = conn.getResponseCode();
        if (i == 200) {
            BufferedReader j = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder k = new StringBuilder();
            String l;
            while ((l = j.readLine()) != null) {
                k.append(l);
            }
            j.close();
            return k.toString();
        } else {
            throw new Exception("HTTP " + i);
        }
    }

    // [Deobfuscated] d -> shutdown * Shuts down the object 'd'
    public void shutdown() {
        // shutdown
        d.shutdown();
    }
}
