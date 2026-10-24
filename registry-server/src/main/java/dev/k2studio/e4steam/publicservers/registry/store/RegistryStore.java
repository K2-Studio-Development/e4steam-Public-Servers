package dev.k2studio.e4steam.publicservers.registry.store;

import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.ReportReason;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomic persistence operations required by the registry service. */
public interface RegistryStore extends AutoCloseable {
    void initialize();

    void insertChallenge(StoredChallenge challenge);

    Optional<StoredChallenge> findChallenge(String challengeId);

    boolean consumeChallengeAndInsertReceipt(String challengeId, StoredReceipt receipt, Instant now);

    CreateResult createPublicationFromReceipt(byte[] receiptHash, PublicationDraft draft, Instant now);

    Optional<StoredPublication> findPublication(String listingId, Instant now);

    MutationResult mutate(String listingId, String leaseId, byte[] leaseProofHash,
                          long expectedRevision, ListingMetadata metadata, Instant now,
                          Instant newExpiry, MutationKind kind);

    PageSlice page(String afterListingId, int limit, PublicServerType type, Instant now);

    JoinIssueResult issueJoinHandle(String listingId, byte[] handleHash, Instant expiresAt, Instant now);

    Optional<String> consumeJoinHandle(byte[] handleHash, Instant now);

    ReportResult addReport(String reportId, String listingId, ReportReason reason,
                           byte[] reporterHash, Instant now);

    ModerationResult moderate(String auditId, String listingId, ModerationAction action,
                              String reasonCode, String actorRef, Instant now);

    void purgeExpired(Instant now, Instant auditCutoff);

    boolean ping();

    @Override
    void close();

    record StoredChallenge(String challengeId, String nonce, PublicServerType type, String action,
                           String audience, Instant expiresAt, Instant consumedAt) {
        public StoredChallenge {
            Objects.requireNonNull(challengeId, "challengeId");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record StoredReceipt(byte[] receiptHash, String ownerRef, PublicServerType type, String action,
                         Instant expiresAt) {
        public StoredReceipt {
            receiptHash = Objects.requireNonNull(receiptHash, "receiptHash").clone();
            Objects.requireNonNull(ownerRef, "ownerRef");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override public byte[] receiptHash() { return receiptHash.clone(); }
    }

    record PublicationDraft(String listingId, PublicServerType type, ListingMetadata metadata,
                            String targetRef, String leaseId, byte[] leaseProofHash,
                            Instant lastHeartbeat, Instant expiresAt) {
        public PublicationDraft {
            Objects.requireNonNull(listingId, "listingId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(targetRef, "targetRef");
            Objects.requireNonNull(leaseId, "leaseId");
            leaseProofHash = Objects.requireNonNull(leaseProofHash, "leaseProofHash").clone();
            Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override public byte[] leaseProofHash() { return leaseProofHash.clone(); }
    }

    record StoredPublication(String listingId, long revision, PublicServerType type,
                             ListingMetadata metadata, String targetRef, String ownerRef,
                             String leaseId, byte[] leaseProofHash, Instant lastHeartbeat,
                             Instant expiresAt) {
        public StoredPublication {
            Objects.requireNonNull(listingId, "listingId");
            if (revision < 1) throw new IllegalArgumentException("invalid revision");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(targetRef, "targetRef");
            Objects.requireNonNull(ownerRef, "ownerRef");
            Objects.requireNonNull(leaseId, "leaseId");
            leaseProofHash = Objects.requireNonNull(leaseProofHash, "leaseProofHash").clone();
            Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override public byte[] leaseProofHash() { return leaseProofHash.clone(); }

        public PublicServerEntry entry() {
            return new PublicServerEntry(listingId, revision, type, metadata,
                    lastHeartbeat, expiresAt, ownerRef);
        }
    }

    enum CreateStatus {
        CREATED,
        INVALID_RECEIPT,
        OWNER_DENIED,
        DUPLICATE
    }

    record CreateResult(CreateStatus status, StoredPublication publication) {
        public CreateResult {
            Objects.requireNonNull(status, "status");
        }
    }

    enum MutationKind {
        HEARTBEAT,
        UPDATE,
        DELETE
    }

    enum MutationStatus {
        UPDATED,
        DELETED,
        NOT_FOUND,
        UNAUTHORIZED,
        REVISION_CONFLICT
    }

    record MutationResult(MutationStatus status, StoredPublication publication) {
        public MutationResult {
            Objects.requireNonNull(status, "status");
        }
    }

    record PageSlice(List<StoredPublication> publications, boolean hasMore) {
        public PageSlice {
            publications = List.copyOf(publications);
        }
    }

    enum JoinIssueStatus {
        ISSUED,
        NOT_FOUND
    }

    record JoinIssueResult(JoinIssueStatus status, String targetRef) {
        public JoinIssueResult {
            Objects.requireNonNull(status, "status");
        }
    }

    enum ReportResult {
        ACCEPTED,
        DUPLICATE,
        NOT_FOUND
    }

    enum ModerationAction {
        REMOVE_LISTING,
        BLOCK_OWNER
    }

    enum ModerationResult {
        APPLIED,
        NOT_FOUND
    }
}
