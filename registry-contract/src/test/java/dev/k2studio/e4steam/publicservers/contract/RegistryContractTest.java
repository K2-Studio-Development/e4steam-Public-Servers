package dev.k2studio.e4steam.publicservers.contract;

import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.AttestationReceipt;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RegistryContractTest {
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    @Test void directoryPageRoundTripsWithoutCredentialFields() {
        DirectoryPage page = new DirectoryPage(List.of(entry()), "cursor.ABC123",
                InstantStamp.of(NOW));
        byte[] json = RegistryJsonCodec.encode(page);
        assertEquals(page, RegistryJsonCodec.decode(json, DirectoryPage.class));
        String text = new String(json, StandardCharsets.UTF_8);
        assertFalse(text.contains("targetRef"));
        assertFalse(text.contains("leaseProof"));
    }

    @Test void strictDecoderRejectsUnknownFieldsAndOversizedRequests() {
        byte[] unknown = "{\"type\":\"INTEGRATED_WORLD\",\"action\":\"PUBLISH_INTEGRATED\",\"extra\":1}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> RegistryJsonCodec.decode(
                unknown, RegistryMessages.ChallengeRequest.class));
        byte[] oversized = new byte[DirectoryProtocol.MAX_REQUEST_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> RegistryJsonCodec.decode(
                oversized, RegistryMessages.ChallengeRequest.class));
    }

    @Test void secretsAreRedactedAndIntegratedCapacityIsEight() {
        AttestationReceipt receipt = new AttestationReceipt("receipt.ABC123", "owner.ABC123",
                InstantStamp.of(NOW.plusSeconds(30)));
        assertFalse(receipt.toString().contains(receipt.receipt()));
        ListingMetadata invalid = metadata(7);
        assertThrows(IllegalArgumentException.class, () -> new CreatePublicationRequest(
                PublicServerType.INTEGRATED_WORLD, invalid, "iw.ABC123", "receipt.ABC123"));
    }

    @Test void displayTextRejectsLinksControlsAndMixedConfusableScripts() {
        assertThrows(IllegalArgumentException.class, () -> TextPolicy.title("https://example.test"));
        assertThrows(IllegalArgumentException.class, () -> TextPolicy.title("Safe\u0000name"));
        assertEquals("Мой Minecraft server", TextPolicy.title("Мой Minecraft server"));
    }

    private static PublicServerEntry entry() {
        return new PublicServerEntry("listing.ABC123", 1, PublicServerType.INTEGRATED_WORLD,
                metadata(8), NOW, NOW.plusSeconds(90), "owner.ABC123");
    }

    private static ListingMetadata metadata(int capacity) {
        return new ListingMetadata(DirectoryProtocol.METADATA_SCHEMA, "Public world", "Survival",
                List.of("survival"), "26.2", "fabric", "0.3.2", 1, "0.1.0-beta.1",
                DirectoryProtocol.VERSION, 1, capacity, "public", Map.of());
    }
}
