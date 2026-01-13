package com.test.obf;

import android.util.Log;
import java.util.UUID;

public class AuthService {

    private String sessionId;

    private boolean isAuthenticated = false;

    // [Deobfuscated] a -> createSessionId * Creates a new session ID using UUID and logs it
    public String createSessionId() {
        sessionId = UUID.randomUUID().toString();
        Log.d("AuthService", "Session created: " + sessionId);
        return sessionId;
    }

    // [Deobfuscated] b -> authenticateUser * Authenticates a user based on their token
    public boolean authenticateUser(String token) {
        if (token != null && token.length() > 10) {
            isAuthenticated = true;
            Log.d("AuthService", "User authenticated");
            return true;
        }
        return false;
    }

    // [Deobfuscated] c -> logoutUser * Logs out the user by setting authentication status to false, session ID to null and logging a message.
    public void logoutUser() {
        isAuthenticated = false;
        sessionId = null;
        Log.d("AuthService", "User logged out");
    }
}
