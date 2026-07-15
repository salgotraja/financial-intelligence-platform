package dev.engnotes.insight.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * Stable group identifiers: a SHA-256 hash of the sorted, pipe-joined member list, truncated to 16
 * hex characters. Sorting before hashing means membership, not construction order, determines the
 * id, so an unchanged group keeps its id across refreshes; a changed membership always changes it.
 */
final class GroupIdGenerator {

    private static final int ID_BYTES = 8;

    private GroupIdGenerator() {}

    static String groupId(Collection<String> members) {
        List<String> sorted = members.stream().sorted().toList();
        String joined = String.join("|", sorted);
        byte[] digest = sha256(joined.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, ID_BYTES);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
