package com.test.obf;

import android.os.Handler;
import java.util.HashMap;
import java.util.Map;

/**
 * 난독화된 ApiService
 * 실제 이름: ApiService
 */
public class j {

    private static j j;

    // NetworkClient
    private c c;

    // AuthService
    private e e;

    // baseUrl
    private String k;

    private j() {
        c = c.getInstanceOrCreate();
        e = e.e();
        k = "https://api.example.com/v1";
    }

    // [Deobfuscated] j -> getInstanceOrCreate * Returns an instance of the class 'j' or creates a new one if it doesn't exist yet.
    public static j getInstanceOrCreate() {
        if (j == null) {
            j = new j();
        }
        return j;
    }

    // [Deobfuscated] a -> authenticateAndGetUserProfile * Authenticates the user and retrieves their profile information
    public void authenticateAndGetUserProfile(String userId, d.i callback) {
        // getUserProfile
        // getToken()
        String token = e.getInstanceOrCreate();
        if (token == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String userProfilePath = "/users/" + userId;
        c.executeGetRequest(userProfilePath, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                String encodedResponse = base64Encode(response, "data");
                callback.executeGetRequest(encodedResponse);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] b -> updateUserProfile * Updates the user profile using a base64 encoded data string and handles success or error responses
    public void updateUserProfile(Map<String, String> data, d.i responseCallback) {
        // updateUserProfile
        // getToken()
        String authenticationToken = e.getInstanceOrCreate();
        if (authenticationToken == null) {
            responseCallback.base64Encode("Not authenticated");
            return;
        }
        String apiEndpoint = "/users/profile";
        // base64
        String encodedData = d.base64Encode(data.toString());
        c.getInstanceOrCreate(apiEndpoint, encodedData, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                responseCallback.executeGetRequest(response);
            }

            @Override
            public void b(String error) {
                // onError
                responseCallback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] c -> getNotifications * Retrieves notifications for a user if authenticated
    public void getNotifications(d.i callback) {
        // getNotifications
        // getToken()
        String authToken = e.getInstanceOrCreate();
        if (authToken == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String notificationEndpoint = "/notifications";
        c.executeGetRequest(notificationEndpoint, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.executeGetRequest(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] d -> markNotificationAsRead * Marks a notification as read if the user is authenticated
    public void markNotificationAsRead(String notificationId, d.i responseCallback) {
        // markNotificationRead
        // getToken()
        String token = e.getInstanceOrCreate();
        if (token == null) {
            responseCallback.base64Encode("Not authenticated");
            return;
        }
        String notificationPath = "/notifications/" + notificationId + "/read";
        c.getInstanceOrCreate(notificationPath, "", new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                responseCallback.executeGetRequest(response);
            }

            @Override
            public void b(String error) {
                // onError
                responseCallback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] e -> onSuccessHandler * Handles successful response from server
    public void onSuccessHandler(String query, int page, d.i callback) {
        // search
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            callback.base64Encode("Not authenticated");
            return;
        }
        String m = "/search?q=" + query + "&page=" + page;
        c.executeGetRequest(m, new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.executeGetRequest(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] b -> extractJsonValueByKey * Extracts the value associated with a specific key in a JSON string
    private String extractJsonValueByKey(String json, String key) {
        // parseJsonKey
        String jsonKeyWithQuotes = "\"" + key + "\":\"";
        int indexOfJsonKeyStart = json.indexOf(jsonKeyWithQuotes);
        if (indexOfJsonKeyStart < 0) {
            return null;
        }
        int startIndexOfValue = indexOfJsonKeyStart + jsonKeyWithQuotes.length();
        int endIndexOfValue = json.indexOf("\"", startIndexOfValue);
        if (endIndexOfValue < 0) {
            return null;
        }
        return json.substring(startIndexOfValue, endIndexOfValue);
    }

    // [Deobfuscated] f -> extractJsonValueByKey * Extracts a value from JSON string by key
    public void extractJsonValueByKey(String username, String email, String password, d.i callback) {
        // register
        Map<String, String> r = new HashMap<>();
        r.put("username", username);
        r.put("email", email);
        r.put("password", password);
        c.getInstanceOrCreate("/auth/register", r.toString(), new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                callback.executeGetRequest(response);
            }

            @Override
            public void b(String error) {
                // onError
                callback.base64Encode(error);
            }
        });
    }

    // [Deobfuscated] g -> registerUser * Registers a new user with the provided credentials
    public void registerUser(String oldPassword, String newPassword, d.i registrationCallback) {
        // changePassword
        // getToken()
        String l = e.getInstanceOrCreate();
        if (l == null) {
            registrationCallback.base64Encode("Not authenticated");
            return;
        }
        Map<String, String> s = new HashMap<>();
        s.put("old_password", oldPassword);
        s.put("new_password", newPassword);
        c.getInstanceOrCreate("/auth/change-password", s.toString(), new d.i() {

            @Override
            public void a(String response) {
                // onSuccess
                registrationCallback.executeGetRequest(response);
            }

            @Override
            public void b(String error) {
                // onError
                registrationCallback.base64Encode(error);
            }
        });
    }
}
