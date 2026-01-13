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
        j = c.downloadImageAndHashNameIfSuccessful();
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

    // [Deobfuscated] a -> downloadImageAndHashNameIfSuccessful * Downloads an image from a URL, generates its MD5 hash and saves it to the cache. If successful, returns the file object of the downloaded image; otherwise, returns null.
    public void downloadImageAndHashNameIfSuccessful(String url, d.i cb) {
        // loadImage
        File cachedImageFile = l.get(url);
        if (cachedImageFile != null && cachedImageFile.exists()) {
            cb.md5HashGenerator(cachedImageFile.getAbsolutePath());
            return;
        }
        File downloadedImageFile = b(url);
        if (downloadedImageFile != null && downloadedImageFile.exists()) {
            l.put(url, downloadedImageFile);
            cb.md5HashGenerator(downloadedImageFile.getAbsolutePath());
            return;
        }
        i.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    File successfullyDownloadedImage = downloadImageAndHashNameIfSuccessful(url);
                    if (successfullyDownloadedImage != null) {
                        l.put(url, successfullyDownloadedImage);
                        cb.md5HashGenerator(successfullyDownloadedImage.getAbsolutePath());
                    } else {
                        cb.b("Failed to download image");
                    }
                } catch (Exception e) {
                    cb.b(e.getMessage());
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

    // [Deobfuscated] c -> downloadImageAndHashNameIfSuccessful * Downloads an image from a URL and hashes the URL to name the file. If the HTTP response code is not 200, it throws an exception.
    private File downloadImageAndHashNameIfSuccessful(String imageUrl) throws Exception {
        // downloadImage
        URL downloadedImageUrl = new URL(imageUrl);
        HttpURLConnection httpConnection = (HttpURLConnection) downloadedImageUrl.openConnection();
        httpConnection.setConnectTimeout(15000);
        httpConnection.setReadTimeout(15000);
        int responseCode = httpConnection.getResponseCode();
        if (responseCode == 200) {
            // md5
            String hashedUrl = d.md5HashGenerator(imageUrl);
            File fileToSaveDownloadedImage = new File(k, hashedUrl + ".jpg");
            FileOutputStream fileOutputStream = new FileOutputStream(fileToSaveDownloadedImage);
            InputStream inputStreamFromHttpResponse = httpConnection.getInputStream();
            byte[] bufferForReadingInputStream = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStreamFromHttpResponse.read(bufferForReadingInputStream)) != -1) {
                fileOutputStream.write(bufferForReadingInputStream, 0, bytesRead);
            }
            inputStreamFromHttpResponse.close();
            fileOutputStream.close();
            return fileToSaveDownloadedImage;
        } else {
            throw new Exception("HTTP " + responseCode);
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

    // [Deobfuscated] f -> shutdownService * Shuts down a service
    public long shutdownService() {
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
