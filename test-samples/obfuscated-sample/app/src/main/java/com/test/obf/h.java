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

    // NetworkClient
    private c j;

    // cacheDir
    private File k;

    // memoryCache
    private ConcurrentHashMap<String, File> l;

    private h() {
        i = Executors.newFixedThreadPool(3);
        j = c.c();
        k = new File("image_cache");
        k.mkdirs();
        l = new ConcurrentHashMap<>();
    }

    // [Deobfuscated] h -> getInstance * Returns an instance of the class h, creating it if necessary.
    public static h getInstance() {
        if (h == null) {
            h = new h();
        }
        return h;
    }

    public void a(String imageUrl, d.i callback) {
        // loadImage
        File m = l.get(imageUrl);
        if (m != null && m.exists()) {
            callback.md5HashGenerator(m.getAbsolutePath());
            return;
        }
        File n = b(imageUrl);
        if (n != null && n.exists()) {
            l.put(imageUrl, n);
            callback.md5HashGenerator(n.getAbsolutePath());
            return;
        }
        i.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    File o = c(imageUrl);
                    if (o != null) {
                        l.put(imageUrl, o);
                        callback.md5HashGenerator(o.getAbsolutePath());
                    } else {
                        callback.b("Failed to download image");
                    }
                } catch (Exception e) {
                    callback.b(e.getMessage());
                }
            }
        });
    }

    // [Deobfuscated] b -> handleExceptionAndCallback * Handles an exception and calls a callback method with the error message.
    private File handleExceptionAndCallback(String url) {
        // getCachedImage
        // md5 hash
        String p = d.md5HashGenerator(url);
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
            // md5
            String s = d.md5HashGenerator(urlString);
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

    // [Deobfuscated] d -> clearCacheAndDeleteFiles * clears a cache and deletes all files in a directory
    public void clearCacheAndDeleteFiles() {
        // clearCache
        l.clear();
        File[] filesInDirectory = k.listFiles();
        if (filesInDirectory != null) {
            for (File currentFile : filesInDirectory) {
                currentFile.delete();
            }
        }
    }

    // [Deobfuscated] e -> shutdownService * Shuts down a service
    public void shutdownService() {
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
