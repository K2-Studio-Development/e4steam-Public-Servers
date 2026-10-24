package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeRequest;

import java.time.Clock;
import java.util.concurrent.CompletionStage;

/** Coordinates registry challenge and core attestation without exposing a Steam auth ticket. */
public final class PublisherAttestationCoordinator {
    public record AttestedTarget(CoreDirectoryGateway.PublicationTarget target,
                                 CoreDirectoryGateway.AttestationReceipt receipt) {
        public AttestedTarget {
            if (target == null || receipt == null) throw new NullPointerException("attestedTarget");
        }
        @Override public String toString() { return "AttestedTarget{redacted,generation=" + target.generation() + '}'; }
    }

    private final PublicDirectoryClient client;
    private final CoreDirectoryGateway core;
    private final Clock clock;

    public PublisherAttestationCoordinator(PublicDirectoryClient client,
                                           CoreDirectoryGateway core, Clock clock) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.core = java.util.Objects.requireNonNull(core, "core");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<AttestedTarget> attest(PublicServerType type) {
        if (!secureOrLoopback(client.origin())) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "publisher attestation requires HTTPS or a loopback development origin"));
        }
        String action = type == PublicServerType.INTEGRATED_WORLD
                ? "PUBLISH_INTEGRATED" : "PUBLISH_DEDICATED";
        // The Steam lobby may become available shortly after the core session
        // reports ACTIVE. Resolve it first so an early attempt does not mint
        // and abandon a one-use registry challenge.
        return core.publicationTarget(type, client.origin()).thenCompose(target ->
                client.requestChallenge(new ChallengeRequest(type, action)).thenCompose(challenge -> {
                    String expectedAudience = client.origin().toASCIIString();
                    if (!challenge.audience().equals(expectedAudience)
                            || challenge.expiresAt().instant().isBefore(clock.instant())) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "registry returned an invalid attestation challenge"));
                    }
                    var request = new CoreDirectoryGateway.AttestationRequest(client.origin(),
                            challenge.challengeId(), challenge.nonce(), type,
                            challenge.expiresAt().instant());
                    return core.attest(request).thenApply(receipt -> {
                        if (!receipt.expiresAt().isAfter(clock.instant())) {
                            throw new IllegalStateException(
                                    "publisher attestation expired before use");
                        }
                        return new AttestedTarget(target, receipt);
                    });
                }));
    }

    private static boolean secureOrLoopback(java.net.URI origin) {
        if ("https".equalsIgnoreCase(origin.getScheme())) return true;
        String host = origin.getHost();
        return "http".equalsIgnoreCase(origin.getScheme()) && host != null
                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1"));
    }
}
