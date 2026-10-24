package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import link.e4steam.api.ApiError;
import link.e4steam.api.ApiResult;
import link.e4steam.api.directory.PublicDirectoryService;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Direct type-safe adapter to public API 1.1. No internal packages or reflective access. */
public final class E4steamCoreDirectoryGateway implements CoreDirectoryGateway {
    private final PublicDirectoryService service;

    public E4steamCoreDirectoryGateway(PublicDirectoryService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    @Override
    public Availability availability() {
        PublicDirectoryService.DirectoryAvailability value = unwrap(service.availability());
        return Availability.valueOf(value.name());
    }

    @Override
    public CompletionStage<PublicationTarget> publicationTarget(PublicServerType type, URI origin) {
        var request = new PublicDirectoryService.PublicationTargetRequest(kind(type),
                new PublicDirectoryService.DirectoryOrigin(origin.toASCIIString()));
        return map(service.publicationTarget(request), value ->
                new PublicationTarget(value.targetRef().value(), value.generation()));
    }

    @Override
    public CompletionStage<AttestationReceipt> attest(AttestationRequest request) {
        var challenge = new PublicDirectoryService.AttestationChallenge(
                new PublicDirectoryService.DirectoryOrigin(request.origin().toASCIIString()),
                request.challengeId(), request.nonce(), action(request.type()),
                request.expiresAt().toEpochMilli());
        return map(service.attest(challenge), value -> new AttestationReceipt(
                value.ownerRef(), value.receiptRef(), Instant.ofEpochMilli(value.expiresAtEpochMillis())));
    }

    @Override
    public CompletionStage<JoinOperation> join(String targetRef, String joinHandle) {
        var request = new PublicDirectoryService.JoinRequest(
                new PublicDirectoryService.OpaqueTargetRef(targetRef),
                new PublicDirectoryService.JoinHandleRef(joinHandle));
        return map(service.join(request), E4steamCoreDirectoryGateway::operation);
    }

    @Override
    public CompletionStage<JoinOperation> cancel(String operationId) {
        return map(service.cancel(new PublicDirectoryService.JoinOperationId(operationId)),
                E4steamCoreDirectoryGateway::operation);
    }

    @Override
    public JoinOperation joinSnapshot(String operationId) {
        return operation(unwrap(service.joinSnapshot(
                new PublicDirectoryService.JoinOperationId(operationId))));
    }

    private static PublicDirectoryService.TargetKind kind(PublicServerType type) {
        if (type == null) throw new NullPointerException("type");
        return PublicDirectoryService.TargetKind.valueOf(type.name());
    }

    private static PublicDirectoryService.AttestationAction action(PublicServerType type) {
        return type == PublicServerType.INTEGRATED_WORLD
                ? PublicDirectoryService.AttestationAction.PUBLISH_INTEGRATED
                : PublicDirectoryService.AttestationAction.PUBLISH_DEDICATED;
    }

    private static JoinOperation operation(PublicDirectoryService.JoinOperation value) {
        return new JoinOperation(value.id().value(), JoinState.valueOf(value.state().name()),
                value.detailCode());
    }

    private static <T, R> CompletionStage<R> map(CompletionStage<ApiResult<T>> stage,
                                                  Function<T, R> mapper) {
        if (stage == null) return CompletableFuture.failedFuture(
                new IllegalStateException("e4steam returned a null completion stage"));
        return stage.thenApply(result -> mapper.apply(unwrap(result)));
    }

    private static <T> T unwrap(ApiResult<T> result) {
        if (result == null) throw new IllegalStateException("e4steam returned a null result");
        if (result.isSuccess()) return result.value().orElseThrow(
                () -> new IllegalStateException("e4steam returned an empty success"));
        ApiError error = result.error().orElseThrow(
                () -> new IllegalStateException("e4steam returned an empty failure"));
        throw new CoreDirectoryException(error);
    }
}
