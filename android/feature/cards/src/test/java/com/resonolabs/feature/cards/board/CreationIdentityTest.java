package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class CreationIdentityTest {
    private static final CreationIdentity OPEN =
            new CreationIdentity("c1", "hash1", "/v1/creations/c1/assets/index.html", "local_archive");

    @Test public void unrelatedCatalogChangeKeepsTheOpenCreation() {
        assertTrue(CreationIdentity.stillOffered(OPEN, List.of(
                new CreationIdentity("c2", "hash2", "https://example.com", "rabbit_qr_link"),
                new CreationIdentity("c1", "hash1", "/v1/creations/c1/assets/index.html", "local_archive"))));
    }

    @Test public void removedOrReplacedCreationCloses() {
        assertFalse(CreationIdentity.stillOffered(OPEN, List.of(
                new CreationIdentity("c2", "hash2", "https://example.com", "rabbit_qr_link"))));
        assertFalse(CreationIdentity.stillOffered(OPEN, List.of(
                new CreationIdentity("c1", "hash9", "/v1/creations/c1/assets/index.html", "local_archive"))));
        assertFalse(CreationIdentity.stillOffered(OPEN, List.of(
                new CreationIdentity("c1", "hash1", "/v1/creations/c1/assets/app.html", "local_archive"))));
        assertFalse(CreationIdentity.stillOffered(null, List.of(OPEN)));
    }
}
