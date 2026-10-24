package dev.k2studio.e4steam.publicservers.registry.store;

import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
import dev.k2studio.e4steam.publicservers.contract.InstantStamp;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.RegistryJsonCodec;
import dev.k2studio.e4steam.publicservers.contract.ReportReason;
import dev.k2studio.e4steam.publicservers.registry.SecureTokens;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** SQLite production store with explicit schema migrations and transactional replay checks. */
public final class SqliteRegistryStore implements RegistryStore {
    private static final int SCHEMA_VERSION = 1;
    private static final String MIGRATION_RESOURCE = "/db/migration/V1__initial.sql";

    private final Path databasePath;
    private final String jdbcUrl;
    private final int busyTimeoutMillis;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SqliteRegistryStore(Path databasePath) {
        this(databasePath, 2_000);
    }

    public SqliteRegistryStore(Path databasePath, int busyTimeoutMillis) {
        Objects.requireNonNull(databasePath, "databasePath");
        if (busyTimeoutMillis < 100 || busyTimeoutMillis > 30_000) {
            throw new IllegalArgumentException("invalid SQLite busy timeout");
        }
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.databasePath;
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    @Override
    public void initialize() {
        requireOpen();
        preparePath();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                        + "version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
                int version = schemaVersion(connection);
                if (version == 0) {
                    applyMigration(statement, MIGRATION_RESOURCE);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)") ) {
                        insert.setInt(1, SCHEMA_VERSION);
                        insert.setLong(2, Instant.now().toEpochMilli());
                        insert.executeUpdate();
                    }
                } else if (version != SCHEMA_VERSION) {
                    throw new StoreFailure("unsupported database schema");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw failure(failure);
        }
        hardenDatabaseFile();
    }

    @Override
    public void insertChallenge(StoredChallenge challenge) {
        requireOpen();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO challenges(challenge_id, nonce, listing_type, action, audience, expires_at, consumed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL)")) {
            statement.setString(1, challenge.challengeId());
            statement.setString(2, challenge.nonce());
            statement.setString(3, challenge.type().name());
            statement.setString(4, challenge.action());
            statement.setString(5, challenge.audience());
            statement.setLong(6, millis(challenge.expiresAt()));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw failure(failure);
        }
    }

    @Override
    public Optional<StoredChallenge> findChallenge(String challengeId) {
        requireOpen();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT challenge_id, nonce, listing_type, action, audience, expires_at, consumed_at "
                        + "FROM challenges WHERE challenge_id = ?")) {
            statement.setString(1, challengeId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Long consumed = nullableLong(rows, "consumed_at");
                return Optional.of(new StoredChallenge(rows.getString("challenge_id"),
                        rows.getString("nonce"), type(rows.getString("listing_type")),
                        rows.getString("action"), rows.getString("audience"),
                        instant(rows.getLong("expires_at")), consumed == null ? null : instant(consumed)));
            }
        } catch (SQLException failure) {
            throw failure(failure);
        }
    }

    @Override
    public boolean consumeChallengeAndInsertReceipt(String challengeId, StoredReceipt receipt,
                                                    Instant now) {
        return transaction(connection -> {
            StoredChallenge challenge;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT challenge_id, nonce, listing_type, action, audience, expires_at, consumed_at "
                            + "FROM challenges WHERE challenge_id = ?")) {
                select.setString(1, challengeId);
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) return false;
                    Long consumed = nullableLong(rows, "consumed_at");
                    challenge = new StoredChallenge(rows.getString("challenge_id"), rows.getString("nonce"),
                            type(rows.getString("listing_type")), rows.getString("action"),
                            rows.getString("audience"), instant(rows.getLong("expires_at")),
                            consumed == null ? null : instant(consumed));
                }
            }
            if (challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now)
                    || challenge.type() != receipt.type() || !challenge.action().equals(receipt.action())) {
                return false;
            }
            try (PreparedStatement consume = connection.prepareStatement(
                    "UPDATE challenges SET consumed_at = ? WHERE challenge_id = ? AND consumed_at IS NULL")) {
                consume.setLong(1, millis(now));
                consume.setString(2, challengeId);
                if (consume.executeUpdate() != 1) return false;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO attestation_receipts(receipt_hash, owner_ref, listing_type, action, "
                            + "expires_at, consumed_at) VALUES (?, ?, ?, ?, ?, NULL)")) {
                insert.setBytes(1, receipt.receiptHash());
                insert.setString(2, receipt.ownerRef());
                insert.setString(3, receipt.type().name());
                insert.setString(4, receipt.action());
                insert.setLong(5, millis(receipt.expiresAt()));
                insert.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public CreateResult createPublicationFromReceipt(byte[] receiptHash, PublicationDraft draft,
                                                     Instant now) {
        return transaction(connection -> {
            ReceiptRow receipt = findReceipt(connection, receiptHash);
            if (receipt == null || receipt.consumedAt != null || !receipt.expiresAt.isAfter(now)
                    || receipt.type != draft.type()
                    || !publicationAction(draft.type()).equals(receipt.action)) {
                return new CreateResult(CreateStatus.INVALID_RECEIPT, null);
            }
            if (isDenied(connection, receipt.ownerRef)) {
                return new CreateResult(CreateStatus.OWNER_DENIED, null);
            }
            try (PreparedStatement deleteExpired = connection.prepareStatement(
                    "DELETE FROM publications WHERE owner_ref = ? AND listing_type = ? AND expires_at <= ?")) {
                deleteExpired.setString(1, receipt.ownerRef);
                deleteExpired.setString(2, draft.type().name());
                deleteExpired.setLong(3, millis(now));
                deleteExpired.executeUpdate();
            }
            if (hasPublication(connection, receipt.ownerRef, draft.type(), now)) {
                return new CreateResult(CreateStatus.DUPLICATE, null);
            }
            try (PreparedStatement consume = connection.prepareStatement(
                    "UPDATE attestation_receipts SET consumed_at = ? "
                            + "WHERE receipt_hash = ? AND consumed_at IS NULL")) {
                consume.setLong(1, millis(now));
                consume.setBytes(2, receiptHash);
                if (consume.executeUpdate() != 1) {
                    return new CreateResult(CreateStatus.INVALID_RECEIPT, null);
                }
            }
            StoredPublication publication = new StoredPublication(draft.listingId(), 1, draft.type(),
                    draft.metadata(), draft.targetRef(), receipt.ownerRef, draft.leaseId(),
                    draft.leaseProofHash(), draft.lastHeartbeat(), draft.expiresAt());
            insertPublication(connection, publication, now);
            return new CreateResult(CreateStatus.CREATED, publication);
        });
    }

    @Override
    public Optional<StoredPublication> findPublication(String listingId, Instant now) {
        requireOpen();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                publicationSelect() + " WHERE listing_id = ? AND expires_at > ?")) {
            statement.setString(1, listingId);
            statement.setLong(2, millis(now));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readPublication(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failure(failure);
        }
    }

    @Override
    public MutationResult mutate(String listingId, String leaseId, byte[] leaseProofHash,
                                 long expectedRevision, ListingMetadata metadata, Instant now,
                                 Instant newExpiry, MutationKind kind) {
        return transaction(connection -> {
            StoredPublication current = findPublication(connection, listingId);
            if (current == null || !current.expiresAt().isAfter(now)) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM publications WHERE listing_id = ? AND expires_at <= ?")) {
                    delete.setString(1, listingId);
                    delete.setLong(2, millis(now));
                    delete.executeUpdate();
                }
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
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM publications WHERE listing_id = ? AND revision = ?")) {
                    delete.setString(1, listingId);
                    delete.setLong(2, expectedRevision);
                    return delete.executeUpdate() == 1
                            ? new MutationResult(MutationStatus.DELETED, null)
                            : new MutationResult(MutationStatus.REVISION_CONFLICT, null);
                }
            }
            ListingMetadata nextMetadata = kind == MutationKind.UPDATE ? metadata : current.metadata();
            StoredPublication updated = new StoredPublication(current.listingId(),
                    current.revision() + 1, current.type(), nextMetadata, current.targetRef(),
                    current.ownerRef(), current.leaseId(), current.leaseProofHash(), now, newExpiry);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE publications SET revision = ?, entry_json = ?, last_heartbeat = ?, expires_at = ? "
                            + "WHERE listing_id = ? AND revision = ?")) {
                update.setLong(1, updated.revision());
                update.setBytes(2, encodeEntry(updated));
                update.setLong(3, millis(now));
                update.setLong(4, millis(newExpiry));
                update.setString(5, listingId);
                update.setLong(6, expectedRevision);
                return update.executeUpdate() == 1
                        ? new MutationResult(MutationStatus.UPDATED, updated)
                        : new MutationResult(MutationStatus.REVISION_CONFLICT, null);
            }
        });
    }

    @Override
    public PageSlice page(String afterListingId, int limit, PublicServerType type, Instant now) {
        requireOpen();
        String sql = publicationSelect() + " WHERE expires_at > ? AND listing_id > ?"
                + (type == null ? "" : " AND listing_type = ?")
                + " ORDER BY listing_id ASC LIMIT ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, millis(now));
            statement.setString(index++, afterListingId == null ? "" : afterListingId);
            if (type != null) statement.setString(index++, type.name());
            statement.setInt(index, limit + 1);
            List<StoredPublication> result = new ArrayList<>(limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readPublication(rows));
            }
            boolean more = result.size() > limit;
            return new PageSlice(more ? result.subList(0, limit) : result, more);
        } catch (SQLException failure) {
            throw failure(failure);
        }
    }

    @Override
    public JoinIssueResult issueJoinHandle(String listingId, byte[] handleHash, Instant expiresAt,
                                           Instant now) {
        return transaction(connection -> {
            StoredPublication publication = findPublication(connection, listingId);
            if (publication == null || !publication.expiresAt().isAfter(now)) {
                return new JoinIssueResult(JoinIssueStatus.NOT_FOUND, null);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO join_handles(handle_hash, listing_id, expires_at, consumed_at) "
                            + "VALUES (?, ?, ?, NULL)")) {
                insert.setBytes(1, handleHash);
                insert.setString(2, listingId);
                insert.setLong(3, millis(expiresAt));
                insert.executeUpdate();
            }
            return new JoinIssueResult(JoinIssueStatus.ISSUED, publication.targetRef());
        });
    }

    @Override
    public Optional<String> consumeJoinHandle(byte[] handleHash, Instant now) {
        return transaction(connection -> {
            String listingId;
            Instant expiry;
            Long consumedAt;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT listing_id, expires_at, consumed_at FROM join_handles WHERE handle_hash = ?")) {
                select.setBytes(1, handleHash);
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    listingId = rows.getString("listing_id");
                    expiry = instant(rows.getLong("expires_at"));
                    consumedAt = nullableLong(rows, "consumed_at");
                }
            }
            if (consumedAt != null || !expiry.isAfter(now)) return Optional.empty();
            StoredPublication publication = findPublication(connection, listingId);
            if (publication == null || !publication.expiresAt().isAfter(now)) return Optional.empty();
            try (PreparedStatement consume = connection.prepareStatement(
                    "UPDATE join_handles SET consumed_at = ? WHERE handle_hash = ? AND consumed_at IS NULL")) {
                consume.setLong(1, millis(now));
                consume.setBytes(2, handleHash);
                if (consume.executeUpdate() != 1) return Optional.empty();
            }
            return Optional.of(publication.targetRef());
        });
    }

    @Override
    public ReportResult addReport(String reportId, String listingId, ReportReason reason,
                                  byte[] reporterHash, Instant now) {
        return transaction(connection -> {
            StoredPublication publication = findPublication(connection, listingId);
            if (publication == null || !publication.expiresAt().isAfter(now)) {
                return ReportResult.NOT_FOUND;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO reports(report_id, listing_id, owner_ref, reason, "
                            + "reporter_hash, created_at, status) VALUES (?, ?, ?, ?, ?, ?, 'OPEN')")) {
                insert.setString(1, reportId);
                insert.setString(2, listingId);
                insert.setString(3, publication.ownerRef());
                insert.setString(4, reason.name());
                insert.setBytes(5, reporterHash);
                insert.setLong(6, millis(now));
                return insert.executeUpdate() == 1 ? ReportResult.ACCEPTED : ReportResult.DUPLICATE;
            }
        });
    }

    @Override
    public ModerationResult moderate(String auditId, String listingId, ModerationAction action,
                                     String reasonCode, String actorRef, Instant now) {
        return transaction(connection -> {
            StoredPublication publication = findPublication(connection, listingId);
            if (publication == null) return ModerationResult.NOT_FOUND;
            if (action == ModerationAction.BLOCK_OWNER) {
                try (PreparedStatement deny = connection.prepareStatement(
                        "INSERT INTO owner_denylist(owner_ref, reason_code, created_at) VALUES (?, ?, ?) "
                                + "ON CONFLICT(owner_ref) DO NOTHING")) {
                    deny.setString(1, publication.ownerRef());
                    deny.setString(2, reasonCode);
                    deny.setLong(3, millis(now));
                    deny.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM publications WHERE owner_ref = ?")) {
                    delete.setString(1, publication.ownerRef());
                    delete.executeUpdate();
                }
            } else {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM publications WHERE listing_id = ?")) {
                    delete.setString(1, listingId);
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement audit = connection.prepareStatement(
                    "INSERT INTO moderation_audit(audit_id, listing_id, owner_ref, action, "
                            + "reason_code, actor_ref, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                audit.setString(1, auditId);
                audit.setString(2, listingId);
                audit.setString(3, publication.ownerRef());
                audit.setString(4, action.name());
                audit.setString(5, reasonCode);
                audit.setString(6, actorRef);
                audit.setLong(7, millis(now));
                audit.executeUpdate();
            }
            return ModerationResult.APPLIED;
        });
    }

    @Override
    public void purgeExpired(Instant now, Instant auditCutoff) {
        transaction(connection -> {
            deleteBefore(connection, "publications", "expires_at", now);
            deleteBefore(connection, "challenges", "expires_at", now);
            deleteBefore(connection, "attestation_receipts", "expires_at", now);
            deleteBefore(connection, "join_handles", "expires_at", now);
            deleteBefore(connection, "moderation_audit", "created_at", auditCutoff);
            return null;
        });
    }

    @Override
    public boolean ping() {
        requireOpen();
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            return rows.next() && rows.getInt(1) == 1;
        } catch (SQLException failure) {
            return false;
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public Path databasePath() {
        return databasePath;
    }

    private Connection open() throws SQLException {
        requireOpen();
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
        }
        return connection;
    }

    private <T> T transaction(SqlWork<T> work) {
        requireOpen();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof SQLException sql) throw failure(sql);
                throw failure;
            }
        } catch (SQLException failure) {
            throw failure(failure);
        }
    }

    private void preparePath() {
        Path parent = databasePath.getParent();
        if (parent == null) throw new StoreFailure("database parent is unavailable");
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent) || (Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(databasePath)
                    || !Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS)))) {
                throw new StoreFailure("unsafe database path");
            }
        } catch (IOException failure) {
            throw new StoreFailure("database path unavailable", failure);
        }
    }

    private void hardenDatabaseFile() {
        try {
            if (Files.getFileStore(databasePath).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(databasePath,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException failure) {
            throw new StoreFailure("database permissions unavailable", failure);
        }
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void applyMigration(Statement statement, String resource) throws SQLException {
        String migration;
        try (InputStream input = SqliteRegistryStore.class.getResourceAsStream(resource)) {
            if (input == null) throw new StoreFailure("database migration missing");
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StoreFailure("database migration unreadable", failure);
        }
        for (String command : migration.split(";")) {
            String sql = command.trim();
            if (!sql.isEmpty()) statement.execute(sql);
        }
    }

    private static ReceiptRow findReceipt(Connection connection, byte[] hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_ref, listing_type, action, expires_at, consumed_at "
                        + "FROM attestation_receipts WHERE receipt_hash = ?")) {
            statement.setBytes(1, hash);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Long consumed = nullableLong(rows, "consumed_at");
                return new ReceiptRow(rows.getString("owner_ref"), type(rows.getString("listing_type")),
                        rows.getString("action"), instant(rows.getLong("expires_at")),
                        consumed == null ? null : instant(consumed));
            }
        }
    }

    private static boolean isDenied(Connection connection, String ownerRef) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM owner_denylist WHERE owner_ref = ?")) {
            statement.setString(1, ownerRef);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static boolean hasPublication(Connection connection, String ownerRef,
                                          PublicServerType type, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM publications WHERE owner_ref = ? AND listing_type = ? AND expires_at > ?")) {
            statement.setString(1, ownerRef);
            statement.setString(2, type.name());
            statement.setLong(3, millis(now));
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static void insertPublication(Connection connection, StoredPublication publication,
                                          Instant createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO publications(listing_id, revision, listing_type, entry_json, target_ref, "
                        + "owner_ref, lease_id, lease_proof_hash, last_heartbeat, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, publication.listingId());
            statement.setLong(2, publication.revision());
            statement.setString(3, publication.type().name());
            statement.setBytes(4, encodeEntry(publication));
            statement.setString(5, publication.targetRef());
            statement.setString(6, publication.ownerRef());
            statement.setString(7, publication.leaseId());
            statement.setBytes(8, publication.leaseProofHash());
            statement.setLong(9, millis(publication.lastHeartbeat()));
            statement.setLong(10, millis(publication.expiresAt()));
            statement.setLong(11, millis(createdAt));
            statement.executeUpdate();
        }
    }

    private static StoredPublication findPublication(Connection connection, String listingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                publicationSelect() + " WHERE listing_id = ?")) {
            statement.setString(1, listingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readPublication(rows) : null;
            }
        }
    }

    private static String publicationSelect() {
        return "SELECT listing_id, revision, listing_type, entry_json, target_ref, owner_ref, "
                + "lease_id, lease_proof_hash, last_heartbeat, expires_at FROM publications";
    }

    private static StoredPublication readPublication(ResultSet rows) throws SQLException {
        byte[] encoded = rows.getBytes("entry_json");
        DirectoryPage wrapper;
        try {
            wrapper = RegistryJsonCodec.decode(encoded, DirectoryPage.class);
        } catch (RuntimeException corrupt) {
            throw new StoreFailure("corrupt publication payload", corrupt);
        }
        if (wrapper.entries().size() != 1) throw new StoreFailure("corrupt publication payload");
        PublicServerEntry entry = wrapper.entries().getFirst();
        String listingId = rows.getString("listing_id");
        long revision = rows.getLong("revision");
        PublicServerType type = type(rows.getString("listing_type"));
        String ownerRef = rows.getString("owner_ref");
        Instant heartbeat = instant(rows.getLong("last_heartbeat"));
        Instant expiry = instant(rows.getLong("expires_at"));
        if (!entry.listingId().equals(listingId) || entry.revision() != revision
                || entry.type() != type || !entry.ownerRef().equals(ownerRef)
                || !entry.lastHeartbeat().equals(heartbeat) || !entry.expiresAt().equals(expiry)) {
            throw new StoreFailure("publication columns do not match payload");
        }
        return new StoredPublication(listingId, revision, type, entry.metadata(),
                rows.getString("target_ref"), ownerRef, rows.getString("lease_id"),
                rows.getBytes("lease_proof_hash"), heartbeat, expiry);
    }

    private static byte[] encodeEntry(StoredPublication publication) {
        return RegistryJsonCodec.encode(new DirectoryPage(List.of(publication.entry()), "",
                InstantStamp.of(publication.lastHeartbeat())));
    }

    private static void deleteBefore(Connection connection, String table, String column,
                                     Instant cutoff) throws SQLException {
        /* Table and column names are fixed internal constants at every call site. */
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " <= ?")) {
            statement.setLong(1, millis(cutoff));
            statement.executeUpdate();
        }
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static PublicServerType type(String value) {
        try {
            return PublicServerType.valueOf(value);
        } catch (IllegalArgumentException corrupt) {
            throw new StoreFailure("corrupt listing type", corrupt);
        }
    }

    private static String publicationAction(PublicServerType type) {
        return type == PublicServerType.INTEGRATED_WORLD
                ? "PUBLISH_INTEGRATED" : "PUBLISH_DEDICATED";
    }

    private static long millis(Instant value) { return value.toEpochMilli(); }
    private static Instant instant(long value) { return Instant.ofEpochMilli(value); }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static StoreFailure failure(SQLException failure) {
        return new StoreFailure("SQLite operation failed", failure);
    }

    private void requireOpen() {
        if (closed.get()) throw new StoreFailure("store is closed");
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record ReceiptRow(String ownerRef, PublicServerType type, String action,
                              Instant expiresAt, Instant consumedAt) { }

    private static final class StoreFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private StoreFailure(String message) { super(message); }
        private StoreFailure(String message, Throwable cause) { super(message, cause); }
    }
}
