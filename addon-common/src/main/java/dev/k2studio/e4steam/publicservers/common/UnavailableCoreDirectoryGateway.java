package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.PublicServerType;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Safe fallback when API 1.1 service or a required capability is unavailable. */
final class UnavailableCoreDirectoryGateway implements CoreDirectoryGateway {
    @Override public Availability availability() { return Availability.UNSUPPORTED; }
    @Override public CompletionStage<PublicationTarget> publicationTarget(PublicServerType type, URI origin) { return failed(); }
    @Override public CompletionStage<AttestationReceipt> attest(AttestationRequest request) { return failed(); }
    @Override public CompletionStage<JoinOperation> join(String targetRef, String joinHandle) { return failed(); }
    @Override public CompletionStage<JoinOperation> cancel(String operationId) { return failed(); }
    @Override public JoinOperation joinSnapshot(String operationId) { throw new IllegalStateException("public directory API unavailable"); }
    private static <T> CompletionStage<T> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("public directory API unavailable"));
    }
}
