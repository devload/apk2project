package com.test.obf;

import android.view.View;
import android.util.Log;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.util.Map;

/**
 * 난독화된 MainActivity
 * 실제 이름: MainActivity
 */
public class a extends Activity {

    // DataManager
    private b b;

    // AuthService
    private e e;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        b = b.getInstance();
        e = e.getInstance();
        // getToken()
        String c = e.getAuthToken();
        // getUserData()
        Map<String, String> d = b.getUserData();
        if (d != null && d.size() > 0) {
            String f = d.get("user_id");
            String g = d.get("user_name");
            displayUserInfo(f, g);
        } else {
            showLoginScreen();
        }
        logActivityCreationTime();
    }

    // [Deobfuscated] a -> displayUserInfo * Displays user information on the screen
    private void displayUserInfo(String id, String username) {
        // displayUserInfo
        TextView textView = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append("User: ").append(username).append("\n");
        sb.append("ID: ").append(id);
        textView.setText(sb.toString());
        setContentView(textView);
    }

    // [Deobfuscated] h -> showLoginScreen * Displays a login prompt to the user
    private void showLoginScreen() {
        // showLoginScreen
        TextView loginPromptTextView = new TextView(this);
        loginPromptTextView.setText("Please login");
        setContentView(loginPromptTextView);
    }

    // [Deobfuscated] i -> logActivityCreationTime * Logs the current time in milliseconds and a string indicating that an activity has been created.
    private void logActivityCreationTime() {
        // logActivity
        long currentTimeMillis = System.currentTimeMillis();
        b.getAuthToken(currentTimeMillis, "activity_created");
    }
}
