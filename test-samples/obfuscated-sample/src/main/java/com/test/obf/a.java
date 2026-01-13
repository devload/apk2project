package com.test.obf;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.util.Map;

/**
 * 난독화된 MainActivity
 * 실제 이름: MainActivity
 */
public class a extends Activity {

    private b b;  // DataManager
    private e e;  // AuthService

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        b = b.b();
        e = e.e();

        String c = e.c();  // getToken()
        Map<String, String> d = b.d();  // getUserData()

        if (d != null && d.size() > 0) {
            String f = d.get("user_id");
            String g = d.get("user_name");
            a(f, g);
        } else {
            h();
        }

        i();
    }

    private void a(String userId, String userName) {
        // displayUserInfo
        TextView textView = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append("User: ").append(userName).append("\n");
        sb.append("ID: ").append(userId);
        textView.setText(sb.toString());
        setContentView(textView);
    }

    private void h() {
        // showLoginScreen
        TextView textView = new TextView(this);
        textView.setText("Please login");
        setContentView(textView);
    }

    private void i() {
        // logActivity
        long j = System.currentTimeMillis();
        b.c(j, "activity_created");
    }
}
