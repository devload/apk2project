package com.test.obf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 난독화된 ImageLoader
 * 실제 이름: ImageLoader
 */
public class h {

    private static h h;
    private ExecutorService i;
    private c j;  // NetworkClient
    private File k;  // cacheDir
    private ConcurrentHashMap<String, File> l;  // memoryCache

    private h() {
        i = Executors.newFixedThreadPool(3);
        j = c.c();
        k = new File("image_cache");
        k.mkdirs();
        l = new ConcurrentHashMap<>();
    }

    public static h h() {
        if (h == null) {
            h = new h();
        }
        return h;
    }

    public void a(String imageUrl, d.i callback) {
        // loadImage
        File m = l.get(imageUrl);
        if (m != null && m.exists()) {
            callback.a(m.getAbsolutePath());
            return;
        }

        File n = b(imageUrl);
        if (n != null && n.exists()) {
            l.put(imageUrl, n);
            callback.a(n.getAbsolutePath());
            return;
        }

        i.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File o = c(imageUrl);
                    if (o != null) {
                        l.put(imageUrl, o);
                        callback.a(o.getAbsolutePath());
                    } else {
                        callback.b("Failed to download image");
                    }
                } catch (Exception e) {
                    callback.b(e.getMessage());
                }
            }
        });
    }

    private File b(String url) {
        // getCachedImage
        String p = d.a(url);  // md5 hash
        return new File(k, p + ".jpg");
    }

    private File c(String urlString) throws Exception {
        // downloadImage
        URL url = new URL(urlString);
        HttpURLConnection q = (HttpURLConnection) url.openConnection();
        q.setConnectTimeout(15000);
        q.setReadTimeout(15000);

        int r = q.getResponseCode();
        if (r == 200) {
            String s = d.a(urlString);  // md5
            File t = new File(k, s + ".jpg");
            FileOutputStream u = new FileOutputStream(t);
            InputStream v = q.getInputStream();
            byte[] w = new byte[4096];
            int x;
            while ((x = v.read(w)) != -1) {
                u.write(w, 0, x);
            }
            v.close();
            u.close();
            return t;
        } else {
            throw new Exception("HTTP " + r);
        }
    }

    public void d() {
        // clearCache
        l.clear();
        File[] e = k.listFiles();
        if (e != null) {
            for (File f : e) {
                f.delete();
            }
        }
    }

    public void e() {
        // shutdown
        i.shutdown();
    }

    public long f() {
        // getCacheSize
        long g = 0;
        File[] h = k.listFiles();
        if (h != null) {
            for (File i : h) {
                g += i.length();
            }
        }
        return g;
    }
}
