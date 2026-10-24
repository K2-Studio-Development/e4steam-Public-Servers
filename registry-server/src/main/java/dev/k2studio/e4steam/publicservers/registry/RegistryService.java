package dev.k2studio.e4steam.publicservers.registry;

import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
import dev.k2studio.e4steam.publicservers.contract.DirectoryProtocol;
import dev.k2studio.e4steam.publicservers.contract.InstantStamp;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryJsonCodec;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.AttestationReceipt;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.LeaseMutationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.PublicationLease;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ReportRequest;
import dev.k2studio.e4steam.publicservers.contract.TextPolicy;
import dev.k2studio.e4steam.publicservers.registry.attestation.PublisherAttestationVerifier;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.CreateStatus;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.JoinIssueStatus;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.ModerationAction;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.ModerationResult;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.MutationKind;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.MutationStatus;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.PublicationDraft;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.StoredChallenge;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.StoredPublication;
import dev.k2studio.e4steam.publicservers.registry.store.RegistryStore.StoredReceipt;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Loader-independent registry state machine with explicit replay and revision checks. */
public final class RegistryService implements AutoCloseable {
    /* Keep headroom below the contract's 256 KiB response ceiling. */
    private static final int CURRENT_CLIENT_PAGE_LIMIT_BYTES = 252 * 1024;

    private final RegistryStore store;
    private final PublisherAttestationVerifier verifier;
    private final Clock clock;
    private final String audience;
    private final Duration challengeTtl;
    private final Duration receiptTtl;
    private final Duration leaseTtl;
    private final Duration joinHandleTtl;
    private final Duration auditRetention;
    private final byte[] cursorKey;
    private final long heartbeatAfterSeconds;

    public RegistryService(RegistryStore store, PublisherAttestationVerifier verifier,
                           Clock clock, String audience, Duration challengeTtl,
                           Duration receiptTtl, Duration leaseTtl, Duration joinHandleTtl,
                           Duration auditRetention, byte[] cursorKey) {
        this.store = Objects.requireNonNull(store, "store");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audience = TextPolicy.identifier(audience, "audience", 128);
        this.challengeTtl = bounded(challengeTtl, 10, 300, "challengeTtl");
        this.receiptTtl = bounded(receiptTtl, 10, 300, "receiptTtl");
        this.leaseTtl = bounded(leaseTtl, DirectoryProtocol.MIN_LEASE_SECONDS,
                DirectoryProtocol.MAX_LEASE_SECONDS, "leaseTtl");
        this.joinHandleTtl = bounded(joinHandleTtl, 5, 60, "joinHandleTtl");
        this.auditRetention = bounded(auditRetention, 86_400, 365L * 86_400, "auditRetention");
        if (cursorKey == null || cursorKey.length < 32) {
            throw new IllegalArgumentException("cursor key is too short");
        }
        this.cursorKey = cursorKey.clone();
        heartbeatAfterSeconds = Math.clamp(this.leaseTtl.toSeconds() / 3, 10, 120);
        store.initialize();
    }

    public ChallengeResponse issueChallenge(ChallengeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!publicationAction(request.type()).equals(request.action())) {
            throw RegistryException.badRequest();
        }
        Instant now = clock.instant();
        StoredChallenge challenge = new StoredChallenge(
                SecureTokens.random("chal.", 24), SecureTokens.random("nonce.", 32),
                request.type(), request.action(), audience, now.plus(challengeTtl), null);
        call(() -> { store.insertChallenge(challenge); return null; });
        return new ChallengeResponse(challenge.challengeId(), challenge.nonce(), challenge.audience(),
                InstantStamp.of(challenge.expiresAt()));
    }

    public AttestationReceipt attest(String challengeId, String assertion) {
        String safeChallengeId = TextPolicy.opaque(challengeId, "challengeId", 96);
        if (assertion == null || assertion.isBlank() || assertion.length() > 768) {
            throw RegistryException.badRequest();
        }
        Instant now = clock.instant();
        StoredChallenge stored = call(() -> store.findChallenge(safeChallengeId))
                .filter(value -> value.consumedAt() == null && value.expiresAt().isAfter(now))
                .orElseThrow(RegistryException::unauthorized);
        PublisherAttestationVerifier.Challenge challenge = new PublisherAttestationVerifier.Challenge(
                stored.challengeId(), stored.nonce(), stored.audience(), stored.action(),
                stored.type(), stored.expiresAt());
        PublisherAttestationVerifier.VerifiedPublisher publisher;
        try {
            publisher = verifier.verify(challenge, assertion, now);
        } catch (PublisherAttestationVerifier.VerificationException rejected) {
            throw RegistryException.unauthorized();
        }
        String ownerRef = TextPolicy.opaque(publisher.ownerRef(), "ownerRef", 96);
        if ((stored.type() == PublicServerType.INTEGRATED_WORLD
                && publisher.authority() != PublisherAttestationVerifier.Authority.STEAM_USER)
                || (stored.type() == PublicServerType.DEDICATED_SERVER
                && publisher.authority() != PublisherAttestationVerifier.Authority.DEDICATED_SERVER)) {
            throw RegistryException.unauthorized();
        }
        String receipt = SecureTokens.random("receipt.", 32);
        Instant expiry = min(now.plus(receiptTtl), stored.expiresAt());
        StoredReceipt storedReceipt = new StoredReceipt(SecureTokens.sha256(receipt), ownerRef,
                stored.type(), stored.action(), expiry);
        boolean consumed = call(() -> store.consumeChallengeAndInsertReceipt(
                stored.challengeId(), storedReceipt, now));
        if (!consumed) throw RegistryException.replay();
        return new AttestationReceipt(receipt, ownerRef, InstantStamp.of(expiry));
    }

    public PublicationLease createPublication(CreatePublicationRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        String listingId = SecureTokens.random("listing.", 24);
        String leaseId = SecureTokens.random("lease.", 24);
        String leaseProof = SecureTokens.random("proof.", 32);
        PublicationDraft draft = new PublicationDraft(listingId, request.type(), request.metadata(),
                request.targetRef(), leaseId, SecureTokens.sha256(leaseProof), now, now.plus(leaseTtl));
        RegistryStore.CreateResult result = call(() -> store.createPublicationFromReceipt(
                SecureTokens.sha256(request.attestationReceipt()), draft, now));
        return switch (result.status()) {
            case CREATED -> lease(result.publication(), leaseProof);
            case INVALID_RECEIPT -> throw RegistryException.replay();
            case OWNER_DENIED -> throw RegistryException.forbidden();
            case DUPLICATE -> throw RegistryException.conflict();
        };
    }

    public PublicationLease heartbeat(String listingId, LeaseMutationRequest request) {
        if (request.metadata() != null) throw RegistryException.badRequest();
        return mutate(listingId, request, MutationKind.HEARTBEAT);
    }

    public PublicationLease update(String listingId, LeaseMutationRequest request) {
        if (request.metadata() == null) throw RegistryException.badRequest();
        return mutate(listingId, request, MutationKind.UPDATE);
    }

    public void delete(String listingId, LeaseMutationRequest request) {
        if (request.metadata() != null) throw RegistryException.badRequest();
        mutate(listingId, request, MutationKind.DELETE);
    }

    private PublicationLease mutate(String listingId, LeaseMutationRequest request,
                                    MutationKind kind) {
        String safeListingId = TextPolicy.opaque(listingId, "listingId", 96);
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        if (kind == MutationKind.UPDATE && request.metadata() != null) {
            Optional<StoredPublication> current = call(() -> store.findPublication(safeListingId, now));
            if (current.isEmpty()) throw RegistryException.notFound();
            if (current.get().type() == PublicServerType.INTEGRATED_WORLD
                    && request.metadata().capacity() != 8) {
                throw RegistryException.badRequest();
            }
        }
        RegistryStore.MutationResult result = call(() -> store.mutate(safeListingId,
                request.leaseId(), SecureTokens.sha256(request.leaseProof()),
                request.expectedRevision(), request.metadata(), now, now.plus(leaseTtl), kind));
        return switch (result.status()) {
            case UPDATED -> lease(result.publication(), request.leaseProof());
            case DELETED -> null;
            case NOT_FOUND -> throw RegistryException.notFound();
            case UNAUTHORIZED -> throw RegistryException.unauthorized();
            case REVISION_CONFLICT -> throw RegistryException.conflict();
        };
    }

    public DirectoryPage catalog(String cursor, int requestedLimit, PublicServerType type) {
        if (requestedLimit < 1 || requestedLimit > DirectoryProtocol.MAX_PAGE_SIZE) {
            throw RegistryException.badRequest();
        }
        String after = decodeCursor(cursor);
        Instant now = clock.instant();
        int limit = requestedLimit;
        while (true) {
            int queryLimit = limit;
            RegistryStore.PageSlice slice = call(() -> store.page(after, queryLimit, type, now));
            List<PublicServerEntry> entries = new ArrayList<>(slice.publications().size());
            slice.publications().forEach(value -> entries.add(value.entry()));
            String next = slice.hasMore() && !slice.publications().isEmpty()
                    ? encodeCursor(slice.publications().getLast().listingId()) : "";
            DirectoryPage page = new DirectoryPage(entries, next, InstantStamp.of(now));
            if (RegistryJsonCodec.encode(page).length <= CURRENT_CLIENT_PAGE_LIMIT_BYTES || limit == 1) {
                return page;
            }
            limit = Math.max(1, limit / 2);
        }
    }

    public Optional<PublicServerEntry> findFresh(String listingId) {
        String safeListingId = TextPolicy.opaque(listingId, "listingId", 96);
        Instant now = clock.instant();
        return call(() -> store.findPublication(safeListingId, now)).map(StoredPublication::entry);
    }

    public JoinHandleResponse issueJoinHandle(JoinHandleRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        Instant expiry = now.plus(joinHandleTtl);
        String handle = SecureTokens.random("join.", 32);
        RegistryStore.JoinIssueResult result = call(() -> store.issueJoinHandle(request.listingId(),
                SecureTokens.sha256(handle), expiry, now));
        if (result.status() == JoinIssueStatus.NOT_FOUND) throw RegistryException.notFound();
        return new JoinHandleResponse(handle, result.targetRef(), InstantStamp.of(expiry));
    }

    /** Core-facing one-time redemption hook for verifier integrations. */
    public String consumeJoinHandle(String handleId) {
        String safeHandleId = TextPolicy.opaque(handleId, "handleId", 128);
        Instant now = clock.instant();
        return call(() -> store.consumeJoinHandle(SecureTokens.sha256(safeHandleId), now))
                .orElseThrow(RegistryException::replay);
    }

    public void report(ReportRequest request, byte[] reporterHash) {
        Objects.requireNonNull(request, "request");
        if (reporterHash == null || reporterHash.length != 32) throw new IllegalArgumentException("reporterHash");
        Instant now = clock.instant();
        RegistryStore.ReportResult result = call(() -> store.addReport(
                SecureTokens.random("report.", 20), request.listingId(), request.reason(),
                reporterHash.clone(), now));
        if (result == RegistryStore.ReportResult.NOT_FOUND) throw RegistryException.notFound();
        /* Duplicate reports intentionally receive the same accepted response. */
    }

    public void moderate(String listingId, ModerationAction action, String reasonCode,
                         String actorRef) {
        String safeListingId = TextPolicy.opaque(listingId, "listingId", 96);
        Objects.requireNonNull(action, "action");
        String safeReasonCode = TextPolicy.identifier(reasonCode, "reasonCode", 64);
        String safeActorRef = TextPolicy.identifier(actorRef, "actorRef", 96);
        Instant now = clock.instant();
        ModerationResult result = call(() -> store.moderate(SecureTokens.random("audit.", 20),
                safeListingId, action, safeReasonCode, safeActorRef, now));
        if (result == ModerationResult.NOT_FOUND) throw RegistryException.notFound();
    }

    public void purgeExpired() {
        Instant now = clock.instant();
        call(() -> {
            store.purgeExpired(now, now.minus(auditRetention));
            return null;
        });
    }

    public Health health() {
        boolean storeReady;
        try {
            storeReady = store.ping();
        } catch (RuntimeException failure) {
            storeReady = false;
        }
        return new Health(storeReady, verifier.available(), DirectoryProtocol.VERSION,
                DirectoryProtocol.METADATA_SCHEMA);
    }

    @Override
    public void close() {
        store.close();
    }

    private PublicationLease lease(StoredPublication publication, String leaseProof) {
        return new PublicationLease(publication.entry(), publication.leaseId(), leaseProof,
                heartbeatAfterSeconds);
    }

    private String encodeCursor(String listingId) {
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(
                listingId.getBytes(StandardCharsets.UTF_8));
        String signature = SecureTokens.base64(SecureTokens.hmac(cursorKey, "cursor-v1\n" + value));
        return "c1." + value + '.' + signature;
    }

    private String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 256) throw RegistryException.badRequest();
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 3 || !"c1".equals(parts[0])) throw new IllegalArgumentException();
            byte[] expected = SecureTokens.hmac(cursorKey, "cursor-v1\n" + parts[1]);
            if (!SecureTokens.equal(expected, SecureTokens.decodeBase64(parts[2], 64))) {
                throw new IllegalArgumentException();
            }
            String listingId = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return TextPolicy.opaque(listingId, "listingId", 96);
        } catch (RuntimeException rejected) {
            throw RegistryException.badRequest();
        }
    }

    private <T> T call(StoreCall<T> operation) {
        try {
            return operation.run();
        } catch (RegistryException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw RegistryException.unavailable();
        }
    }

    private static Duration bounded(Duration value, long minimumSeconds, long maximumSeconds,
                                    String field) {
        if (value == null || value.isNegative() || value.isZero()
                || value.toSeconds() < minimumSeconds || value.toSeconds() > maximumSeconds) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    public static String publicationAction(PublicServerType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case INTEGRATED_WORLD -> "PUBLISH_INTEGRATED";
            case DEDICATED_SERVER -> "PUBLISH_DEDICATED";
        };
    }

    @FunctionalInterface
    private interface StoreCall<T> {
        T run();
    }

    public record Health(boolean storeReady, boolean attestationReady,
                         int directoryProtocol, int metadataSchema) { }
}
