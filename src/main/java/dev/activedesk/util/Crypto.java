package dev.activedesk.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final int ITER = 120_000;

    private Crypto() {}

    public static String token(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return HEX.formatHex(b);
    }

    /** Numeric PIN, easy to type on a phone. Security comes from Tailscale identity + rate limiting + this. */
    public static String pin(int digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    public static String hashSecret(String secret) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return "pbkdf2$" + ITER + "$" + HEX.formatHex(salt) + "$" + HEX.formatHex(pbkdf2(secret, salt, ITER));
    }

    public static boolean verifySecret(String secret, String stored) {
        if (secret == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
        int iter = Integer.parseInt(p[1]);
        byte[] salt = HEX.parseHex(p[2]);
        byte[] expect = HEX.parseHex(p[3]);
        return MessageDigest.isEqual(expect, pbkdf2(secret, salt, iter));
    }

    private static byte[] pbkdf2(String secret, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, iter, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) { return HEX.formatHex(b); }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
