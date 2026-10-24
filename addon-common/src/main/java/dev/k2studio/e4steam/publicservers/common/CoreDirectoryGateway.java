package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.PublicServerType;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Narrow pure-Java port around e4steam API 1.1's public directory service. */
public interface CoreDirectoryGateway {
    enum Availability { AVAILABLE, NO_ACTIVE_SESSION, STEAM_UNAVAILABLE, UNSUPPORTED }

    enum JoinState {
        IDLE, RESOLVING, JOINING_STEAM_TARGET, AUTHENTICATING, CONNECTING_MINECRAFT,
        ACTIVE, CANCELLED, STALE, FULL, INCOMPATIBLE, FAILED
    }

    record PublicationTarget(String targetRef, long generation) {
        public PublicationTarget {
            if (targetRef == null || targetRef.isBlank() || targetRef.length() > 512) {
                throw new IllegalArgumentException("invalid targetRef");
            }
            if (generation <= 0L) throw new IllegalArgumentException("invalid generation");
        }
        @Override public String toString() { return "PublicationTarget{redacted,generation=" + generation + '}'; }
    }

    record AttestationReceipt(String ownerRef, String receiptRef, Instant expiresAt) {
        public AttestationReceipt {
            if (ownerRef == null || ownerRef.isBlank() || ownerRef.length() > 512
                    || receiptRef == null || receiptRef.isBlank() || receiptRef.length() > 512
                    || expiresAt == null) throw new IllegalArgumentException("invalid attestation receipt");
        }
        @Override public String toString() { return "AttestationReceipt{redacted,expires=" + expiresAt + '}'; }
    }

    record AttestationRequest(URI origin, String challengeId, String nonce,
                              PublicServerType type, Instant expiresAt) {
        public AttestationRequest {
            if (origin == null || challengeId == null || nonce == null || type == null || expiresAt == null) {
                throw new NullPointerException("attestation request");
            }
        }
        @Override public String toString() { return "AttestationRequest{origin=" + origin + ",type=" + type + '}'; }
    }

    record JoinOperation(String id, JoinState state, String detailCode) {
        public JoinOperation {
            if (id == null || id.isBlank() || state == null) throw new IllegalArgumentException("invalid join operation");
            detailCode = detailCode == null ? "" : detailCode;
        }
    }

    Availability availability();

    CompletionStage<PublicationTarget> publicationTarget(PublicServerType type, URI origin);

    CompletionStage<AttestationReceipt> attest(AttestationRequest request);

    CompletionStage<JoinOperation> join(String targetRef, String joinHandle);

    CompletionStage<JoinOperation> cancel(String operationId);

    JoinOperation joinSnapshot(String operationId);
}
