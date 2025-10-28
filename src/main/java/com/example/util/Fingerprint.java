package com.example.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Fingerprint {
    private Fingerprint(){}
    public static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "fp_err";
        }
    }
}
