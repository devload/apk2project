package com.test.obf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 난독화된 Validator
 * 실제 이름: Validator
 */
public class i {

    private static i i;
    private List<String> j;  // errorMessages

    private i() {
        j = new ArrayList<>();
    }

    public static i i() {
        if (i == null) {
            i = new i();
        }
        return i;
    }

    public boolean a(String email) {
        // validateEmail
        j.clear();
        if (d.g(email)) {
            j.add("Email is required");
            return false;
        }

        if (!d.d(email)) {
            j.add("Invalid email format");
            return false;
        }

        if (email.length() > 100) {
            j.add("Email is too long");
            return false;
        }

        return true;
    }

    public boolean b(String password) {
        // validatePassword
        j.clear();

        if (d.g(password)) {
            j.add("Password is required");
            return false;
        }

        if (password.length() < 8) {
            j.add("Password must be at least 8 characters");
            return false;
        }

        if (!c(password)) {
            j.add("Password must contain at least one uppercase letter");
            return false;
        }

        if (!d(password)) {
            j.add("Password must contain at least one digit");
            return false;
        }

        return true;
    }

    public boolean c(String username) {
        // validateUsername
        j.clear();

        if (d.g(username)) {
            j.add("Username is required");
            return false;
        }

        if (username.length() < 3) {
            j.add("Username must be at least 3 characters");
            return false;
        }

        if (username.length() > 20) {
            j.add("Username is too long");
            return false;
        }

        if (!e(username)) {
            j.add("Username can only contain letters, numbers, and underscore");
            return false;
        }

        return true;
    }

    private boolean c(String password) {
        // hasUpperCase
        for (char k : password.toCharArray()) {
            if (Character.isUpperCase(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean d(String password) {
        // hasDigit
        for (char k : password.toCharArray()) {
            if (Character.isDigit(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean e(String username) {
        // isValidUsername
        return Pattern.compile("^[a-zA-Z0-9_]+$").matcher(username).matches();
    }

    public List<String> f() {
        // getErrors
        return new ArrayList<>(j);
    }

    public String g() {
        // getErrorMessage
        return String.join(", ", j);
    }
}
