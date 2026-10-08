package com.resonolabs.feature.cards.board;

import java.util.List;
import java.util.Objects;

/**
 * What makes an open Creation "the same" across catalog refreshes. A catalog generation bump
 * (any install, toggle or removal) must not close an unrelated open Creation; only a change to
 * the open one (removed, disabled, replaced content, new entry) does. Pure Java.
 */
public final class CreationIdentity {
    public final String creationId;
    public final String contentHash;
    public final String entry;
    public final String sourceType;

    public CreationIdentity(String creationId, String contentHash, String entry, String sourceType) {
        this.creationId = creationId == null ? "" : creationId;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.entry = entry == null ? "" : entry;
        this.sourceType = sourceType == null ? "" : sourceType;
    }

    public boolean sameAs(CreationIdentity other) {
        return other != null && creationId.equals(other.creationId) && contentHash.equals(other.contentHash)
                && entry.equals(other.entry) && sourceType.equals(other.sourceType);
    }

    /** True when the open Creation is still offered unchanged by the refreshed catalog. */
    public static boolean stillOffered(CreationIdentity open, List<CreationIdentity> catalog) {
        if (open == null || open.creationId.isEmpty()) return false;
        for (CreationIdentity item : catalog) if (open.sameAs(item)) return true;
        return false;
    }

    @Override public boolean equals(Object other) {
        return other instanceof CreationIdentity && sameAs((CreationIdentity) other);
    }

    @Override public int hashCode() { return Objects.hash(creationId, contentHash, entry, sourceType); }
}
