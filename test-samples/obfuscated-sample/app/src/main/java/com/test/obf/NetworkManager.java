package com.test.obf;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NetworkManager {

    private static NetworkManager instance;

    private String apiKey;

    private NetworkManager() {
        this.apiKey = "default_key";
    }

    // [Deobfuscated] a -> getInstance * Returns an instance of NetworkManager. If no instance exists, it creates a new one.
    public static NetworkManager getInstance() {
        if (instance == null) {
            instance = new NetworkManager();
        }
        return instance;
    }

    // [Deobfuscated] b -> fetchDataFromURL * Fetches data from a URL using GET request with Bearer token authorization
    public String fetchDataFromURL(String urlToFetch) {
        try {
            URL urlObject = new URL(urlToFetch);
            HttpURLConnection connectionToUrl = (HttpURLConnection) urlObject.openConnection();
            connectionToUrl.setRequestMethod("GET");
            connectionToUrl.setRequestProperty("Authorization", "Bearer " + apiKey);
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(connectionToUrl.getInputStream()));
            StringBuilder responseBuilder = new StringBuilder();
            String currentLine;
            while ((currentLine = inputReader.readLine()) != null) {
                responseBuilder.append(currentLine);
            }
            inputReader.close();
            return responseBuilder.toString();
        } catch (Exception e) {
            Log.e("NetworkManager", "Error fetching data", e);
            return null;
        }
    }

    // [Deobfuscated] c -> updateApiKey * updates the API key and logs a debug message
    public void updateApiKey(String updatedApiKey) {
        this.apiKey = updatedApiKey;
        Log.d("NetworkManager", "API key updated");
    }
}
