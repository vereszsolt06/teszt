package hu.sporolos.store;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/** Azonosítók: a profil-azonosító titok is (aki ismeri, látja a háztartás listáját), ezért véletlen és hosszú. */
public final class Ids {

    private static final Pattern SAFE = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");
    private static final SecureRandom RND = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private Ids() {}

    /** Csak kisbetű, szám és kötőjel – fájlnévként és URL-ben is biztonságos. */
    public static String check(String id) {
        if (id == null || !SAFE.matcher(id).matches()) throw new IllegalArgumentException("Érvénytelen azonosító");
        return id;
    }

    public static boolean valid(String id) {
        return id != null && SAFE.matcher(id).matches();
    }

    /** 20 karakteres véletlen profil-azonosító (~100 bit). */
    public static String newProfileId() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) sb.append(ALPHABET[RND.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
