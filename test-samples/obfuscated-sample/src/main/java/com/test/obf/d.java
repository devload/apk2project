package com.test.obf;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 난독화된 Utils
 * 실제 이름: Utils
 */
public class d {

    /**
     * 콜백 인터페이스
     */
    public interface i {
        void a(String response);  // onSuccess
        void b(String error);      // onError
    }

    public static String a(String input) {
        // md5
        try {
            MessageDigest b = MessageDigest.getInstance("MD5");
            byte[] c = b.digest(input.getBytes());
            StringBuilder d = new StringBuilder();
            for (byte e : c) {
                d.append(String.format("%02x", e));
            }
            return d.toString();
        } catch (Exception f) {
            return "";
        }
    }

    public static String b(String input) {
        // base64Encode
        return Base64.getEncoder().encodeToString(input.getBytes());
    }

    public static String c(String input) {
        // base64Decode
        try {
            byte[] d = Base64.getDecoder().decode(input);
            return new String(d);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean d(String email) {
        // isValidEmail
        if (email == null || email.isEmpty()) {
            return false;
        }
        return Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$")
            .matcher(email)
            .matches();
    }

    public static boolean e(String phone) {
        // isValidPhone
        if (phone == null || phone.isEmpty()) {
            return false;
        }
        return phone.matches("\\d{10,}");
    }

    public static String f(String text, int maxLength) {
        // truncate
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    public static boolean g(String str) {
        // isEmpty
        return str == null || str.trim().isEmpty();
    }

    public static String h(String str) {
        // sanitize
        if (g(str)) {
            return "";
        }
        return str.replaceAll("[<>\"']", "");
    }

    public static int i(String num) {
        // parseInteger
        try {
            return Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }

    public static long j(String num) {
        // parseLong
        try {
            return Long.parseLong(num);
        } catch (Exception e) {
            return 0L;
        }
    }
}
