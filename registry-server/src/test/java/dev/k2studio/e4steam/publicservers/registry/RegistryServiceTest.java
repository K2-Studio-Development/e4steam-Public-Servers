package dev.k2studio.e4steam.publicservers.registry;

import dev.k2studio.e4steam.publicservers.contract.DirectoryProtocol;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.LeaseMutationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.PublicationLease;
import dev.k2studio.e4steam.publicservers.registry.attestation.DevelopmentAttestationVerifier;
import dev.k2studio.e4steam.publicservers.registry.attestation.PublisherAttestationVerifier;
import dev.k2studio.e4steam.publicservers.registry.store.InMemoryRegistryStore;
import dev.k2studio.e4steam.publicservers.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegistryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    @Test void challengeReceiptLeaseAndJoinHandleAreOneUse() {
        MutableClock clock = new MutableClock(NOW);
        byte[] key = key(7);
        try (RegistryService service = service(clock, key)) {
            ChallengeResponse challenge = service.issueChallenge(new ChallengeRequest(
                    PublicServerType.INTEGRATED_WORLD, "PUBLISH_INTEGRATED"));
            PublisherAttestationVerifier.Challenge verifierChallenge =
                    new PublisherAttestationVerifier.Challenge(challenge.challengeId(), challenge.nonce(),
                            challenge.audience(), "PUBLISH_INTEGRATED", PublicServerType.INTEGRATED_WORLD,
                            challenge.expiresAt().instant());
            String assertion = DevelopmentAttestationVerifier.sign(key, verifierChallenge,
                    new PublisherAttestationVerifier.VerifiedPublisher("owner.ABC123",
                            PublisherAttestationVerifier.Authority.STEAM_USER), NOW.plusSeconds(30));
            var receipt = service.attest(challenge.challengeId(), assertion);
            PublicationLease lease = service.createPublication(new CreatePublicationRequest(
                    PublicServerType.INTEGRATED_WORLD, metadata(), "iw.ABC123", receipt.receipt()));
            assertEquals(8, lease.entry().metadata().capacity());
            assertEquals(1, service.catalog("", 10, null).entries().size());
            assertThrows(RegistryException.class, () -> service.createPublication(
                    new CreatePublicationRequest(PublicServerType.INTEGRATED_WORLD, metadata(),
                            "iw.XYZ789", receipt.receipt())));

            JoinHandleResponse handle = service.issueJoinHandle(new JoinHandleRequest(
                    lease.entry().listingId()));
            assertFalse(handle.toString().contains(handle.handleId()));
            assertEquals("iw.ABC123", service.consumeJoinHandle(handle.handleId()));
            RegistryException replay = assertThrows(RegistryException.class,
                    () -> service.consumeJoinHandle(handle.handleId()));
            assertEquals("replay_rejected", replay.code());
        }
    }

    @Test void leaseRevisionAndExpiryFailClosed() {
        MutableClock clock = new MutableClock(NOW);
        byte[] key = key(11);
        try (RegistryService service = service(clock, key)) {
            PublicationLease lease = publish(service, key);
            RegistryException conflict = assertThrows(RegistryException.class, () -> service.heartbeat(
                    lease.entry().listingId(), new LeaseMutationRequest(lease.leaseId(), lease.leaseProof(),
                            99, null)));
            assertEquals(409, conflict.status());
            PublicationLease heartbeat = service.heartbeat(lease.entry().listingId(),
                    new LeaseMutationRequest(lease.leaseId(), lease.leaseProof(), 1, null));
            assertEquals(2, heartbeat.entry().revision());
            clock.advance(Duration.ofSeconds(91));
            assertTrue(service.catalog("", 10, null).entries().isEmpty());
            assertThrows(RegistryException.class,
                    () -> service.issueJoinHandle(new JoinHandleRequest(lease.entry().listingId())));
        }
    }

    @Test void wrongPublisherAuthorityCannotPublishIntegratedWorld() {
        MutableClock clock = new MutableClock(NOW);
        byte[] key = key(19);
        try (RegistryService service = service(clock, key)) {
            ChallengeResponse response = service.issueChallenge(new ChallengeRequest(
                    PublicServerType.INTEGRATED_WORLD, "PUBLISH_INTEGRATED"));
            PublisherAttestationVerifier.Challenge challenge = new PublisherAttestationVerifier.Challenge(
                    response.challengeId(), response.nonce(), response.audience(), "PUBLISH_INTEGRATED",
                    PublicServerType.INTEGRATED_WORLD, response.expiresAt().instant());
            String assertion = DevelopmentAttestationVerifier.sign(key, challenge,
                    new PublisherAttestationVerifier.VerifiedPublisher("server.ABC123",
                            PublisherAttestationVerifier.Authority.DEDICATED_SERVER), NOW.plusSeconds(30));
            assertThrows(RegistryException.class, () -> service.attest(response.challengeId(), assertion));
        }
    }

    private static PublicationLease publish(RegistryService service, byte[] key) {
        ChallengeResponse response = service.issueChallenge(new ChallengeRequest(
                PublicServerType.INTEGRATED_WORLD, "PUBLISH_INTEGRATED"));
        PublisherAttestationVerifier.Challenge challenge = new PublisherAttestationVerifier.Challenge(
                response.challengeId(), response.nonce(), response.audience(), "PUBLISH_INTEGRATED",
                PublicServerType.INTEGRATED_WORLD, response.expiresAt().instant());
        String assertion = DevelopmentAttestationVerifier.sign(key, challenge,
                new PublisherAttestationVerifier.VerifiedPublisher("owner.ABC123",
                        PublisherAttestationVerifier.Authority.STEAM_USER), NOW.plusSeconds(30));
        var receipt = service.attest(response.challengeId(), assertion);
        return service.createPublication(new CreatePublicationRequest(PublicServerType.INTEGRATED_WORLD,
                metadata(), "iw.ABC123", receipt.receipt()));
    }

    private static RegistryService service(MutableClock clock, byte[] key) {
        return new RegistryService(new InMemoryRegistryStore(), new DevelopmentAttestationVerifier(key),
                clock, "registry.test", Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofSeconds(90), Duration.ofSeconds(30), Duration.ofDays(1), key(31));
    }

    private static ListingMetadata metadata() {
        return new ListingMetadata(DirectoryProtocol.METADATA_SCHEMA, "Public world", "Survival",
                List.of("survival"), "26.2", "fabric", "0.3.2", 1, "0.1.0-beta.1",
                DirectoryProtocol.VERSION, 1, 8, "public", Map.of());
    }

    private static byte[] key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
