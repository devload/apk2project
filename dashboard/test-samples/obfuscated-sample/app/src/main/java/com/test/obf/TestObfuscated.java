package com.test.obf;

import android.util.Log;

public class TestObfuscated {
    public String a(String b, int c) {
        String d = b + ":" + c;
        Log.d("TAG", d);
        return d;
    }
    
    public void e() {
        String f = a("test", 123);
        System.out.println(f);
    }
}
