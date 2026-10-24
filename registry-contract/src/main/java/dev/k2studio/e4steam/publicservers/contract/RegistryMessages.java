package dev.k2studio.e4steam.publicservers.contract;

import java.time.Instant;
import java.util.Objects;

/** Versioned request/response DTOs for the registry HTTP boundary. */
public final class RegistryMessages {
    private RegistryMessages() {
    }

    public record ChallengeRequest(PublicServerType type, String action) {
        public ChallengeRequest {
            type = Objects.requireNonNull(type, "type");
            action = TextPolicy.identifier(action, "action", 32);
        }
    }

    public record ChallengeResponse(String challengeId, String nonce, String audience,
                                    InstantStamp expiresAt) {
        public ChallengeResponse {
            challengeId = TextPolicy.opaque(challengeId, "challengeId", 96);
            nonce = TextPolicy.opaque(nonce, "nonce", 128);
            audience = TextPolicy.identifier(audience, "audience", 128);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record AttestationReceipt(String receipt, String ownerRef, InstantStamp expiresAt) {
        public AttestationReceipt {
            receipt = TextPolicy.opaque(receipt, "receipt", 256);
            ownerRef = TextPolicy.opaque(ownerRef, "ownerRef", 96);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override public String toString() {
            return "AttestationReceipt{receipt=redacted, owner=opaque, expires=" + expiresAt + '}';
        }
    }

    public record CreatePublicationRequest(PublicServerType type, ListingMetadata metadata,
                                           String targetRef, String attestationReceipt) {
        public CreatePublicationRequest {
            type = Objects.requireNonNull(type, "type");
            metadata = Objects.requireNonNull(metadata, "metadata");
            targetRef = TextPolicy.opaque(targetRef, "targetRef", 256);
            attestationReceipt = TextPolicy.opaque(attestationReceipt, "attestationReceipt", 256);
            if (type == PublicServerType.INTEGRATED_WORLD && metadata.capacity() != 8) {
                throw new IllegalArgumentException("integrated worlds must advertise capacity 8");
            }
        }
    }

    public record PublicationLease(PublicServerEntry entry, String leaseId,
                                   String leaseProof, long heartbeatAfterSeconds) {
        public PublicationLease {
            entry = Objects.requireNonNull(entry, "entry");
            leaseId = TextPolicy.opaque(leaseId, "leaseId", 96);
            leaseProof = TextPolicy.opaque(leaseProof, "leaseProof", 256);
            if (heartbeatAfterSeconds < 10 || heartbeatAfterSeconds > 120) {
                throw new IllegalArgumentException("invalid heartbeat interval");
            }
        }

        @Override public String toString() {
            return "PublicationLease{listing=" + entry.listingId() + ", proof=redacted}";
        }
    }

    public record LeaseMutationRequest(String leaseId, String leaseProof, long expectedRevision,
                                       ListingMetadata metadata) {
        public LeaseMutationRequest {
            leaseId = TextPolicy.opaque(leaseId, "leaseId", 96);
            leaseProof = TextPolicy.opaque(leaseProof, "leaseProof", 256);
            if (expectedRevision < 1) throw new IllegalArgumentException("invalid revision");
        }

        @Override public String toString() { return "LeaseMutationRequest{proof=redacted}"; }
    }

    public record JoinHandleRequest(String listingId) {
        public JoinHandleRequest {
            listingId = TextPolicy.opaque(listingId, "listingId", 96);
        }
    }

    public record JoinHandleResponse(String handleId, String targetRef, InstantStamp expiresAt) {
        public JoinHandleResponse {
            handleId = TextPolicy.opaque(handleId, "handleId", 128);
            targetRef = TextPolicy.opaque(targetRef, "targetRef", 256);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        public boolean expired(Instant now) { return !expiresAt.instant().isAfter(now); }
        @Override public String toString() { return "JoinHandleResponse{target=redacted}"; }
    }

    public record ReportRequest(String listingId, ReportReason reason) {
        public ReportRequest {
            listingId = TextPolicy.opaque(listingId, "listingId", 96);
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    public record ErrorResponse(String code, String messageKey, boolean retryable) {
        public ErrorResponse {
            code = TextPolicy.identifier(code, "code", 64);
            messageKey = TextPolicy.identifier(messageKey, "messageKey", 128);
        }
    }
}
