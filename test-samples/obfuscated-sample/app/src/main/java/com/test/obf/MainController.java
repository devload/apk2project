package com.test.obf;

import android.util.Log;

public class MainController {

    private NetworkManager networkManager;

    private DataProcessor dataProcessor;

    public MainController() {
        this.networkManager = NetworkManager.a();
        this.dataProcessor = new DataProcessor();
    }

    // [Deobfuscated] h -> fetchAndProcessData * Fetches data from a network endpoint and processes it
    public void fetchAndProcessData(String networkEndpoint) {
        String responseFromNetwork = networkManager.b(networkEndpoint);
        if (responseFromNetwork != null) {
            dataProcessor.d(responseFromNetwork);
            Log.d("MainController", "Data fetched and stored");
        }
    }

    // [Deobfuscated] i -> getDataProcessorResult * retrieves the result from data processor
    public int getDataProcessorResult() {
        return dataProcessor.e();
    }

    // [Deobfuscated] j -> getDataByIndex * retrieves data by index from a data processor
    public String getDataByIndex(int index) {
        return dataProcessor.f(index);
    }

    // [Deobfuscated] k -> clearData * clears data from the dataProcessor object
    public void clearData() {
        dataProcessor.g();
        Log.d("MainController", "Data cleared");
    }
}
