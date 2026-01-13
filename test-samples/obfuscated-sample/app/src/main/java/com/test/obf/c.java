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

    // [Deobfuscated] a -> executeGetRequest * Executes a GET request and calls the provided callback with the response
    public void executeGetRequest(String requestPath, d.i responseCallback) {
        // get
        d.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    String getResponse = executeRequest(requestPath, "GET");
                    if (responseCallback != null) {
                        responseCallback.a(getResponse);
                    }
                } catch (Exception e) {
                    responseCallback.executeRequest(e.getMessage());
                }
            }
        });
    }

    // [Deobfuscated] c -> executeHttpRequestWithCallback * Executes an HTTP request with a callback function
    public void executeHttpRequestWithCallback(String requestPath, String requestBody, d.i httpCallback) {
        // post
        d.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    String g = executeHttpRequestWithBody(requestPath, requestBody, "POST");
                    if (httpCallback != null) {
                        httpCallback.a(g);
                    }
                } catch (Exception e) {
                    httpCallback.executeRequest(e.getMessage());
                }
            }
        });
    }

    // [Deobfuscated] b -> executeRequest * Executes an HTTP request and returns the response as a string if the status code is 200, otherwise throws an exception.
    private String executeRequest(String requestPath, String httpMethod) throws Exception {
        // executeRequest
        URL requestURL = new URL(e + requestPath);
        HttpURLConnection connection = (HttpURLConnection) requestURL.openConnection();
        connection.setRequestMethod(httpMethod);
        connection.setConnectTimeout(f);
        connection.setReadTimeout(f);
        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            reader.close();
            return stringBuilder.toString();
        } else {
            throw new Exception("HTTP " + responseCode);
        }
    }

    // [Deobfuscated] h -> executeHttpRequestWithBody * Executes an HTTP request with a body and returns the response as a string if successful, otherwise throws an exception.
    private String executeHttpRequestWithBody(String apiPath, String requestBody, String httpMethod) throws Exception {
        // executeWithBody
        URL targetUrl = new URL(e + apiPath);
        HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
        connection.setRequestMethod(httpMethod);
        connection.setConnectTimeout(f);
        connection.setReadTimeout(f);
        connection.setDoOutput(true);
        connection.getOutputStream().write(requestBody.getBytes());
        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = inputReader.readLine()) != null) {
                stringBuilder.append(line);
            }
            inputReader.close();
            return stringBuilder.toString();
        } else {
            throw new Exception("HTTP " + responseCode);
        }
    }

    // [Deobfuscated] d -> shutdown * Shuts down the object 'd'
    public void shutdown() {
        // shutdown
        d.shutdown();
    }
}
