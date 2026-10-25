package com.nishgamerrz.relaydash;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password storage for the built-in web dashboard.
 *
 * The password itself is never stored: only a PBKDF2-HMAC-SHA1 digest and its
 * salt, both hex encoded so they survive SharedPreferences.
 *
 * Pure Java on purpose - it is exercised directly by the JVM test harness.
 */
public final class Auth {

    private static final int ITERATIONS = 20000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private static final String PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private volatile String saltHex = "";
    private volatile String hashHex = "";

    public Auth(String saltHex, String hashHex) {
        if (saltHex != null) this.saltHex = saltHex;
        if (hashHex != null) this.hashHex = hashHex;
    }

    public boolean isConfigured() {
        return saltHex.length() > 0 && hashHex.length() > 0;
    }

    public String getSaltHex() {
        return saltHex;
    }

    public String getHashHex() {
        return hashHex;
    }

    /** Replaces the password with a fresh salt. */
    public synchronized void setPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        saltHex = hex(salt);
        hashHex = hex(pbkdf2(password == null ? "" : password, salt));
    }

    /** Constant-time comparison, so a wrong password cannot be timed. */
    public boolean check(String password) {
        if (!isConfigured()) return false;
        try {
            byte[] expected = unhex(hashHex);
            byte[] actual = pbkdf2(password == null ? "" : password, unhex(saltHex));
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 128-bit random session id. */
    public static String newToken() {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        return hex(raw);
    }

    /**
     * A password that is easy to read off a TV from the sofa:
     * no l/1/i/o/0 ambiguity, six characters, ~1e9 combinations.
     */
    public static String randomPassword(int length) {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(rnd.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // SHA-256 fallback: weaker, but never silently accept nothing.
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(salt);
                return md.digest(password.getBytes("UTF-8"));
            } catch (Exception inner) {
                throw new IllegalStateException("no digest available", inner);
            }
        }
    }

    public static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(out);
    }

    public static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
