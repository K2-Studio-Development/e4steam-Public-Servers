package dev.k2studio.e4steam.publicservers.registry.store;

import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.ReportReason;
import dev.k2studio.e4steam.publicservers.registry.SecureTokens;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic bounded-test store. Production startup uses {@link SqliteRegistryStore}. */
public final class InMemoryRegistryStore implements RegistryStore {
    private final Map<String, StoredChallenge> challenges = new HashMap<>();
    private final Map<String, ReceiptState> receipts = new HashMap<>();
    private final Map<String, StoredPublication> publications = new HashMap<>();
    private final Map<String, JoinState> joinHandles = new HashMap<>();
    private final Set<String> reportFingerprints = new HashSet<>();
    private final Set<String> deniedOwners = new HashSet<>();
    private final List<AuditState> audit = new ArrayList<>();
    private boolean closed;

    @Override
    public synchronized void initialize() {
        requireOpen();
    }

    @Override
    public synchronized void insertChallenge(StoredChallenge challenge) {
        requireOpen();
        if (challenges.putIfAbsent(challenge.challengeId(), challenge) != null) {
            throw new IllegalStateException("challenge collision");
        }
    }

    @Override
    public synchronized Optional<StoredChallenge> findChallenge(String challengeId) {
        requireOpen();
        return Optional.ofNullable(challenges.get(challengeId));
    }

    @Override
    public synchronized boolean consumeChallengeAndInsertReceipt(String challengeId,
                                                                 StoredReceipt receipt,
                                                                 Instant now) {
        requireOpen();
        StoredChallenge challenge = challenges.get(challengeId);
        if (challenge == null || challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now)
                || challenge.type() != receipt.type() || !challenge.action().equals(receipt.action())) {
            return false;
        }
        String receiptKey = key(receipt.receiptHash());
        if (receipts.containsKey(receiptKey)) return false;
        challenges.put(challengeId, new StoredChallenge(challenge.challengeId(), challenge.nonce(),
                challenge.type(), challenge.action(), challenge.audience(), challenge.expiresAt(), now));
        receipts.put(receiptKey, new ReceiptState(receipt, null));
        return true;
    }

    @Override
    public synchronized CreateResult createPublicationFromReceipt(byte[] receiptHash,
                                                                  PublicationDraft draft,
                                                                  Instant now) {
        requireOpen();
        String receiptKey = key(receiptHash);
        ReceiptState state = receipts.get(receiptKey);
        if (state == null || state.consumedAt != null || !state.receipt.expiresAt().isAfter(now)
                || state.receipt.type() != draft.type()
                || !publicationAction(draft.type()).equals(state.receipt.action())) {
            return new CreateResult(CreateStatus.INVALID_RECEIPT, null);
        }
        if (deniedOwners.contains(state.receipt.ownerRef())) {
            return new CreateResult(CreateStatus.OWNER_DENIED, null);
        }
        boolean duplicate = publications.values().stream().anyMatch(existing ->
                existing.ownerRef().equals(state.receipt.ownerRef()) && existing.type() == draft.type()
                        && existing.expiresAt().isAfter(now));
        if (duplicate || publications.containsKey(draft.listingId())) {
            return new CreateResult(CreateStatus.DUPLICATE, null);
        }
        StoredPublication publication = new StoredPublication(draft.listingId(), 1, draft.type(),
                draft.metadata(), draft.targetRef(), state.receipt.ownerRef(), draft.leaseId(),
                draft.leaseProofHash(), draft.lastHeartbeat(), draft.expiresAt());
        receipts.put(receiptKey, new ReceiptState(state.receipt, now));
        publications.put(publication.listingId(), publication);
        return new CreateResult(CreateStatus.CREATED, publication);
    }

    @Override
    public synchronized Optional<StoredPublication> findPublication(String listingId, Instant now) {
        requireOpen();
        StoredPublication publication = publications.get(listingId);
        return publication == null || !publication.expiresAt().isAfter(now)
                ? Optional.empty() : Optional.of(publication);
    }

    @Override
    public synchronized MutationResult mutate(String listingId, String leaseId,
                                              byte[] leaseProofHash, long expectedRevision,
                                              ListingMetadata metadata, Instant now,
                                              Instant newExpiry, MutationKind kind) {
        requireOpen();
        StoredPublication current = publications.get(listingId);
        if (current == null || !current.expiresAt().isAfter(now)) {
            publications.remove(listingId);
            return new MutationResult(MutationStatus.NOT_FOUND, null);
        }
        if (!current.leaseId().equals(leaseId)
                || !SecureTokens.equal(current.leaseProofHash(), leaseProofHash)) {
            return new MutationResult(MutationStatus.UNAUTHORIZED, null);
        }
        if (current.revision() != expectedRevision) {
            return new MutationResult(MutationStatus.REVISION_CONFLICT, current);
        }
        if (kind == MutationKind.DELETE) {
            publications.remove(listingId);
            return new MutationResult(MutationStatus.DELETED, null);
        }
        ListingMetadata nextMetadata = kind == MutationKind.UPDATE ? metadata : current.metadata();
        StoredPublication updated = new StoredPublication(current.listingId(), current.revision() + 1,
                current.type(), nextMetadata, current.targetRef(), current.ownerRef(), current.leaseId(),
                current.leaseProofHash(), now, newExpiry);
        publications.put(listingId, updated);
        return new MutationResult(MutationStatus.UPDATED, updated);
    }

    @Override
    public synchronized PageSlice page(String afterListingId, int limit, PublicServerType type,
                                       Instant now) {
        requireOpen();
        List<StoredPublication> ordered = publications.values().stream()
                .filter(value -> value.expiresAt().isAfter(now))
                .filter(value -> type == null || value.type() == type)
                .filter(value -> afterListingId == null || value.listingId().compareTo(afterListingId) > 0)
                .sorted(Comparator.comparing(StoredPublication::listingId))
                .limit((long) limit + 1)
                .toList();
        boolean more = ordered.size() > limit;
        return new PageSlice(more ? ordered.subList(0, limit) : ordered, more);
    }

    @Override
    public synchronized JoinIssueResult issueJoinHandle(String listingId, byte[] handleHash,
                                                        Instant expiresAt, Instant now) {
        requireOpen();
        StoredPublication publication = publications.get(listingId);
        if (publication == null || !publication.expiresAt().isAfter(now)) {
            return new JoinIssueResult(JoinIssueStatus.NOT_FOUND, null);
        }
        String handleKey = key(handleHash);
        if (joinHandles.putIfAbsent(handleKey,
                new JoinState(listingId, expiresAt, null)) != null) {
            throw new IllegalStateException("join handle collision");
        }
        return new JoinIssueResult(JoinIssueStatus.ISSUED, publication.targetRef());
    }

    @Override
    public synchronized Optional<String> consumeJoinHandle(byte[] handleHash, Instant now) {
        requireOpen();
        String handleKey = key(handleHash);
        JoinState handle = joinHandles.get(handleKey);
        if (handle == null || handle.consumedAt != null || !handle.expiresAt.isAfter(now)) {
            return Optional.empty();
        }
        StoredPublication publication = publications.get(handle.listingId);
        if (publication == null || !publication.expiresAt().isAfter(now)) return Optional.empty();
        joinHandles.put(handleKey, new JoinState(handle.listingId, handle.expiresAt, now));
        return Optional.of(publication.targetRef());
    }

    @Override
    public synchronized ReportResult addReport(String reportId, String listingId,
                                               ReportReason reason, byte[] reporterHash,
                                               Instant now) {
        requireOpen();
        StoredPublication publication = publications.get(listingId);
        if (publication == null || !publication.expiresAt().isAfter(now)) return ReportResult.NOT_FOUND;
        String fingerprint = listingId + ':' + reason.name() + ':' + key(reporterHash);
        return reportFingerprints.add(fingerprint) ? ReportResult.ACCEPTED : ReportResult.DUPLICATE;
    }

    @Override
    public synchronized ModerationResult moderate(String auditId, String listingId,
                                                  ModerationAction action, String reasonCode,
                                                  String actorRef, Instant now) {
        requireOpen();
        StoredPublication publication = publications.get(listingId);
        if (publication == null) return ModerationResult.NOT_FOUND;
        if (action == ModerationAction.BLOCK_OWNER) {
            deniedOwners.add(publication.ownerRef());
            publications.values().removeIf(value -> value.ownerRef().equals(publication.ownerRef()));
        } else {
            publications.remove(listingId);
        }
        audit.add(new AuditState(auditId, listingId, publication.ownerRef(), action,
                reasonCode, actorRef, now));
        return ModerationResult.APPLIED;
    }

    @Override
    public synchronized void purgeExpired(Instant now, Instant auditCutoff) {
        requireOpen();
        publications.values().removeIf(value -> !value.expiresAt().isAfter(now));
        challenges.values().removeIf(value -> !value.expiresAt().isAfter(now));
        receipts.values().removeIf(value -> !value.receipt.expiresAt().isAfter(now));
        joinHandles.values().removeIf(value -> !value.expiresAt.isAfter(now));
        audit.removeIf(value -> value.at.isBefore(auditCutoff));
    }

    @Override
    public synchronized boolean ping() {
        return !closed;
    }

    public synchronized int auditCount() {
        return audit.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        challenges.clear();
        receipts.clear();
        publications.clear();
        joinHandles.clear();
        reportFingerprints.clear();
        deniedOwners.clear();
        audit.clear();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("store is closed");
    }

    private static String key(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static String publicationAction(PublicServerType type) {
        return type == PublicServerType.INTEGRATED_WORLD
                ? "PUBLISH_INTEGRATED" : "PUBLISH_DEDICATED";
    }

    private record ReceiptState(StoredReceipt receipt, Instant consumedAt) { }
    private record JoinState(String listingId, Instant expiresAt, Instant consumedAt) { }
    private record AuditState(String auditId, String listingId, String ownerRef,
                              ModerationAction action, String reasonCode, String actorRef,
                              Instant at) { }
}
