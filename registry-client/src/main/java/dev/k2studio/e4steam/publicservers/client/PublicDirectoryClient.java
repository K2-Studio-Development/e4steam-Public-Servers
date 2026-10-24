package dev.k2studio.e4steam.publicservers.client;

import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.LeaseMutationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.PublicationLease;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ReportRequest;

import java.net.URI;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Bounded asynchronous registry API used by the loader-neutral addon domain. */
public interface PublicDirectoryClient extends AutoCloseable {
    /** Returns the canonical registry origin used for core-bound attestations. */
    URI origin();

    CompletionStage<ChallengeResponse> requestChallenge(ChallengeRequest request);

    CompletionStage<PublicationLease> createPublication(CreatePublicationRequest request);

    CompletionStage<PublicationLease> heartbeat(String listingId, LeaseMutationRequest request);

    CompletionStage<PublicationLease> updatePublication(String listingId, LeaseMutationRequest request);

    CompletionStage<Void> deletePublication(String listingId, LeaseMutationRequest request);

    CompletionStage<DirectoryPage> catalog(int limit, String cursor, PublicServerType type);

    CompletionStage<JoinHandleResponse> createJoinHandle(JoinHandleRequest request);

    CompletionStage<Void> report(ReportRequest request);

    /**
     * Resolves one fresh listing using bounded pagination. The backend intentionally exposes no
     * credential-bearing direct target endpoint; a separate one-use join handle is still required.
     */
    default CompletionStage<Optional<PublicServerEntry>> findListing(
            String listingId,
            PublicServerType type,
            int pageSize,
            int maximumPages
    ) {
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("listingId must not be blank");
        }
        if (maximumPages < 1 || maximumPages > 32) {
            throw new IllegalArgumentException("maximumPages is outside the supported range");
        }
        return findPage(listingId, type, pageSize, maximumPages, "", new HashSet<>());
    }

    private CompletionStage<Optional<PublicServerEntry>> findPage(
            String listingId,
            PublicServerType type,
            int pageSize,
            int remainingPages,
            String cursor,
            Set<String> visitedCursors
    ) {
        if (!visitedCursors.add(cursor)) {
            return CompletableFuture.failedFuture(new DirectoryClientException(
                    DirectoryClientException.Category.PROTOCOL,
                    0,
                    false,
                    0L,
                    ""
            ));
        }
        return catalog(pageSize, cursor, type).thenCompose(page -> {
            for (PublicServerEntry entry : page.entries()) {
                if (entry.listingId().equals(listingId)) {
                    return CompletableFuture.completedFuture(Optional.of(entry));
                }
            }
            if (remainingPages == 1 || page.nextCursor().isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return findPage(listingId, type, pageSize, remainingPages - 1,
                    page.nextCursor(), visitedCursors);
        });
    }

    boolean isClosed();

    /** Cancels queued/in-flight calls and releases all owned worker threads. */
    @Override
    void close();
}
