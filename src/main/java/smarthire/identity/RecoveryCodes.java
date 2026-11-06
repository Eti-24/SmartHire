package smarthire.identity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

final class RecoveryCodes {
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    static List<String> generate() {
        return IntStream.range(0, 6).mapToObj(ignored -> {
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            try { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }).toList();
    }

    static byte[] hash(char[] code) {
        byte[] ascii = new byte[32];
        try {
            if (code != null && code.length == ascii.length) {
                for (int i = 0; i < ascii.length; i++) { ascii[i] = code[i] < 128 ? (byte) code[i] : 0; }
            }
            return MessageDigest.getInstance("SHA-256").digest(ascii);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Recovery hashing is unavailable.", e);
        } finally { Arrays.fill(ascii, (byte) 0); }
    }
}
