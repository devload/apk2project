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

    // errorMessages
    private List<String> j;

    private i() {
        j = new ArrayList<>();
    }

    // [Deobfuscated] i -> getInstance * Returns an instance of the class if it doesn't exist, otherwise returns the existing one.
    public static i getInstance() {
        if (i == null) {
            i = new i();
        }
        return i;
    }

    // [Deobfuscated] a -> validateEmail * validates an email address
    public boolean validateEmail(String userEmail) {
        // validateEmail
        j.clear();
        if (d.g(userEmail)) {
            j.add("Email is required");
            return false;
        }
        if (!d.d(userEmail)) {
            j.add("Invalid email format");
            return false;
        }
        if (userEmail.length() > 100) {
            j.add("Email is too long");
            return false;
        }
        return true;
    }

    // [Deobfuscated] b -> validatePassword * validates the password based on certain conditions
    public boolean validatePassword(String userInput) {
        // validatePassword
        j.clear();
        if (d.g(userInput)) {
            j.add("Password is required");
            return false;
        }
        if (userInput.length() < 8) {
            j.add("Password must be at least 8 characters");
            return false;
        }
        if (!c(userInput)) {
            j.add("Password must contain at least one uppercase letter");
            return false;
        }
        if (!d(userInput)) {
            j.add("Password must contain at least one digit");
            return false;
        }
        return true;
    }

    // [Deobfuscated] c -> containsUppercaseLetter * checks if a string contains an uppercase letter
    public boolean containsUppercaseLetter(String username) {
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

    // [Deobfuscated] c -> containsUppercaseLetter * checks if a string contains an uppercase letter
    private boolean containsUppercaseLetter(String inputPassword) {
        // hasUpperCase
        for (char character : inputPassword.toCharArray()) {
            if (Character.isUpperCase(character)) {
                return true;
            }
        }
        return false;
    }

    // [Deobfuscated] d -> containsDigit * checks if a string contains at least one digit
    private boolean containsDigit(String inputPassword) {
        // hasDigit
        for (char currentChar : inputPassword.toCharArray()) {
            if (Character.isDigit(currentChar)) {
                return true;
            }
        }
        return false;
    }

    // [Deobfuscated] e -> isValidUsername * checks if the provided username is valid according to a specific pattern
    private boolean isValidUsername(String userName) {
        // isValidUsername
        return Pattern.compile("^[a-zA-Z0-9_]+$").matcher(userName).matches();
    }

    // [Deobfuscated] f -> getErrors * Returns a list of errors from the object 'j'
    public List<String> getErrors() {
        // getErrors
        return new ArrayList<>(j);
    }

    // [Deobfuscated] g -> getErrorMessage * Joins the elements of an array into a single string with comma as separator
    public String getErrorMessage() {
        // getErrorMessage
        return String.join(", ", j);
    }
}
