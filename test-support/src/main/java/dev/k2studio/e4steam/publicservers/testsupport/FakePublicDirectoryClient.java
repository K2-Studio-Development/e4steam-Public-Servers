package dev.k2studio.e4steam.publicservers.testsupport;

import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
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
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Scriptable no-network client. Unscripted calls fail so tests cannot pass accidentally. */
public final class FakePublicDirectoryClient implements PublicDirectoryClient {
    private final URI origin;
    private final Queue<CompletableFuture<DirectoryPage>> catalog = new ArrayDeque<>();
    private final Queue<CompletableFuture<JoinHandleResponse>> handles = new ArrayDeque<>();
    private boolean closed;

    public FakePublicDirectoryClient(URI origin) { this.origin = origin; }

    public void enqueueCatalog(DirectoryPage page) {
        catalog.add(CompletableFuture.completedFuture(page));
    }

    public CompletableFuture<DirectoryPage> enqueuePendingCatalog() {
        CompletableFuture<DirectoryPage> future = new CompletableFuture<>();
        catalog.add(future);
        return future;
    }

    public void enqueueJoinHandle(JoinHandleResponse handle) {
        handles.add(CompletableFuture.completedFuture(handle));
    }

    @Override public URI origin() { return origin; }
    @Override public CompletionStage<DirectoryPage> catalog(int limit, String cursor, PublicServerType type) {
        return next(catalog, "catalog");
    }
    @Override public CompletionStage<JoinHandleResponse> createJoinHandle(JoinHandleRequest request) {
        return next(handles, "join handle");
    }

    @Override public CompletionStage<ChallengeResponse> requestChallenge(ChallengeRequest request) {
        return unsupported("challenge");
    }
    @Override public CompletionStage<PublicationLease> createPublication(CreatePublicationRequest request) {
        return unsupported("publication");
    }
    @Override public CompletionStage<PublicationLease> heartbeat(String id, LeaseMutationRequest request) {
        return unsupported("heartbeat");
    }
    @Override public CompletionStage<PublicationLease> updatePublication(String id, LeaseMutationRequest request) {
        return unsupported("update");
    }
    @Override public CompletionStage<Void> deletePublication(String id, LeaseMutationRequest request) {
        return unsupported("delete");
    }
    @Override public CompletionStage<Void> report(ReportRequest request) { return CompletableFuture.completedFuture(null); }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() { closed = true; }

    private static <T> CompletionStage<T> next(Queue<CompletableFuture<T>> queue, String operation) {
        CompletableFuture<T> value = queue.poll();
        return value == null ? unsupported(operation) : value;
    }

    private static <T> CompletionStage<T> unsupported(String operation) {
        return CompletableFuture.failedFuture(new AssertionError("Unscripted " + operation));
    }
}
