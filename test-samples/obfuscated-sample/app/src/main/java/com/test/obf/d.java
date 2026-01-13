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

        // [Deobfuscated] a -> md5HashGenerator * generates an MD5 hash of the input string
        void md5HashGenerator(String response);

        // [Deobfuscated] b -> base64Encode * encodes a string into base64 format
        void base64Encode(String error);
    }

    // [Deobfuscated] a -> md5HashGenerator * generates an MD5 hash of the input string
    public static String md5HashGenerator(String stringToHash) {
        // md5
        try {
            MessageDigest md5DigestInstance = MessageDigest.getInstance("MD5");
            byte[] messageBytes = md5DigestInstance.digest(stringToHash.getBytes());
            StringBuilder hashStringBuilder = new StringBuilder();
            for (byte byteInMessage : messageBytes) {
                hashStringBuilder.append(String.format("%02x", byteInMessage));
            }
            return hashStringBuilder.toString();
        } catch (Exception exception) {
            return "";
        }
    }

    // [Deobfuscated] b -> base64Encode * encodes a string into base64 format
    public static String base64Encode(String userInput) {
        // base64Encode
        return Base64.getEncoder().encodeToString(userInput.getBytes());
    }

    // [Deobfuscated] c -> base64DecodeString * decodes a base64 encoded string
    public static String base64DecodeString(String encodedString) {
        // base64Decode
        try {
            byte[] d = Base64.getDecoder().decode(encodedString);
            return new String(d);
        } catch (Exception e) {
            return "";
        }
    }

    // [Deobfuscated] d -> isValidEmail * checks if an email address is valid
    public static boolean isValidEmail(String userEmail) {
        // isValidEmail
        if (userEmail == null || userEmail.isEmpty()) {
            return false;
        }
        return Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$").matcher(userEmail).matches();
    }

    // [Deobfuscated] e -> isValidPhoneNumber * checks if a phone number is valid
    public static boolean isValidPhoneNumber(String inputPhone) {
        // isValidPhone
        if (inputPhone == null || inputPhone.isEmpty()) {
            return false;
        }
        return inputPhone.matches("\\d{10,}");
    }

    // [Deobfuscated] f -> truncateTextWithEllipsis * Truncates a text to a specified length and appends an ellipsis if the text is longer than the maximum length.
    public static String truncateTextWithEllipsis(String inputText, int maximumAllowedLength) {
        // truncate
        if (inputText == null) {
            return "";
        }
        if (inputText.length() <= maximumAllowedLength) {
            return inputText;
        }
        return inputText.substring(0, maximumAllowedLength) + "...";
    }

    // [Deobfuscated] g -> isEmptyOrBlank * checks if a string is null, empty or contains only whitespace characters
    public static boolean isEmptyOrBlank(String inputString) {
        // isEmpty
        return inputString == null || inputString.trim().isEmpty();
    }

    public static String h(String str) {
        // sanitize
        if (isEmptyOrBlank(str)) {
            return "";
        }
        return str.replaceAll("[<>\"']", "");
    }

    // [Deobfuscated] i -> parseInteger * Attempts to parse a string into an integer. If the parsing fails, it returns 0.
    public static int parseInteger(String inputString) {
        // parseInteger
        try {
            return Integer.parseInt(inputString);
        } catch (Exception e) {
            return 0;
        }
    }

    // [Deobfuscated] j -> parseLongOrZero * Attempts to parse a string into a long. If the parsing fails, it returns 0.
    public static long parseLongOrZero(String inputString) {
        // parseLong
        try {
            return Long.parseLong(inputString);
        } catch (Exception e) {
            return 0L;
        }
    }
}
