package com.test.obf;

import android.util.Log;

public class TestObfuscated {

    // [Deobfuscated] a -> logDebugMessage * Logs a debug message with the format 'string:int' and returns it.
    public String logDebugMessage(String inputString, int inputInt) {
        String formattedMessage = inputString + ":" + inputInt;
        Log.d("TAG", formattedMessage);
        return formattedMessage;
    }

    // [Deobfuscated] e -> logDebugMessage * Logs a debug message with the specified tag and value
    public void logDebugMessage() {
        String debugMessage = logDebugMessage("test", 123);
        System.out.println(debugMessage);
    }
}
