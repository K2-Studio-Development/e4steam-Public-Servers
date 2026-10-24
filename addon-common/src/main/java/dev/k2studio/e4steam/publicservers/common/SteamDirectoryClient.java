package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.client.PublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.contract.*;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.*;
import link.e4steam.api.ApiResult;
import link.e4steam.api.lobby.LobbyService;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Steam matchmaking catalog. This adapter makes no HTTP requests and does not forge attestations. */
final class SteamDirectoryClient implements PublicDirectoryClient {
    // Logical identity for the existing directory DTOs, never an HTTP endpoint.
    static final URI ORIGIN = URI.create("https://steamcommunity.com");
    static final String PREFIX = "k2studio-e4steam_public_servers:";
    private final LobbyService lobbies;
    private final Clock clock;
    private volatile Map<String, Resolved> resolved = Map.of();
    private volatile boolean closed;
    private CompletableFuture<DirectoryPage> pendingSearch;

    SteamDirectoryClient(LobbyService lobbies, Clock clock) {
        this.lobbies = Objects.requireNonNull(lobbies);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public URI origin() { return ORIGIN; }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() { closed = true; resolved = Map.of(); }

    @Override public synchronized CompletionStage<DirectoryPage> catalog(int limit, String cursor, PublicServerType type) {
        if (closed) return unavailable();
        if (!cursor.isEmpty() || type == PublicServerType.DEDICATED_SERVER)
            return CompletableFuture.completedFuture(new DirectoryPage(List.of(), "", InstantStamp.of(clock.instant())));
        if (pendingSearch != null) return pendingSearch;
        CompletableFuture<DirectoryPage> result = new CompletableFuture<>();
        pendingSearch = result;
        try {
            search(limit).whenComplete((page, failure) -> {
                synchronized (this) {
                    if (pendingSearch == result) pendingSearch = null;
                }
                if (failure == null) result.complete(page);
                else result.completeExceptionally(failure);
            });
        } catch (RuntimeException failure) {
            pendingSearch = null;
            result.completeExceptionally(failure);
        }
        return result;
    }

    private CompletionStage<DirectoryPage> search(int limit) {
        if (closed) return unavailable();
        LobbyService.Metadata filters = new LobbyService.Metadata(Map.of(PREFIX + "schema", LobbyService.MetadataValue.text("1")));
        return lobbies.search(new LobbyService.SearchQuery(filters, limit, "")).thenApply(result -> {
            LobbyService.SearchPage page = unwrap(result);
            if (closed) throw new CancellationException("Catalog closed");
            Map<String, Resolved> found = new LinkedHashMap<>();
            List<PublicServerEntry> entries = new ArrayList<>();
            Instant now = clock.instant();
            for (LobbyService.LobbySnapshot lobby : page.results()) {
                try {
                    Resolved value = decode(lobby, now);
                    found.put(value.entry().listingId(), value);
                    entries.add(value.entry());
                } catch (IllegalArgumentException | java.time.DateTimeException ignored) { /* Untrusted advertisement. */ }
            }
            resolved = Map.copyOf(found);
            return new DirectoryPage(entries, "", InstantStamp.of(now));
        });
    }

    static LobbyService.Metadata encode(ListingMetadata data, String target, Instant now) {
        LinkedHashMap<String, LobbyService.MetadataValue> values = new LinkedHashMap<>();
        put(values, "schema", "1"); put(values, "name", data.name());
        put(values, "minecraft", data.minecraftVersion()); put(values, "loader", data.loader());
        put(values, "core", data.e4steamVersion()); put(values, "wire", Integer.toString(data.coreWireProtocol()));
        put(values, "addon", data.addonVersion()); put(values, "players", Integer.toString(data.players()));
        put(values, "capacity", Integer.toString(data.capacity()));
        put(values, "target", target); put(values, "updated", Long.toString(now.getEpochSecond()));
        return new LobbyService.Metadata(values);
    }

    static Resolved decode(LobbyService.LobbySnapshot lobby, Instant now) {
        Map<String, LobbyService.MetadataValue> data = lobby.metadata().values();
        if (!text(data, "schema").equals("1")) throw new IllegalArgumentException("schema");
        Instant updated = Instant.ofEpochSecond(Long.parseLong(text(data, "updated")));
        if (updated.isAfter(now.plusSeconds(15)) || !updated.plusSeconds(45).isAfter(now))
            throw new IllegalArgumentException("stale advertisement");
        String target = text(data, "target");
        // Only the core interprets the opaque reference and authenticates its target.
        if (target.length() > 256 || target.isEmpty()) throw new IllegalArgumentException("target");
        ListingMetadata metadata = new ListingMetadata(DirectoryProtocol.METADATA_SCHEMA, text(data, "name"), "",
                List.of("modded"), text(data, "minecraft"), text(data, "loader"), text(data, "core"),
                Integer.parseInt(text(data, "wire")), text(data, "addon"), DirectoryProtocol.VERSION,
                Integer.parseInt(text(data, "players")), Integer.parseInt(text(data, "capacity")),
                PublicAccessModeProvider.MODE_ID.value(), Map.of());
        if (!PublicServersAddon.validWorldCapacity(metadata.capacity())) throw new IllegalArgumentException("world capacity");
        String id = "steam." + lobby.id().value();
        return new Resolved(new PublicServerEntry(id, Math.max(1, updated.getEpochSecond()),
                PublicServerType.INTEGRATED_WORLD, metadata, updated, updated.plusSeconds(45),
                "lobby." + lobby.id().value()), target);
    }
    private static void put(Map<String, LobbyService.MetadataValue> values, String key, String value) {
        values.put(PREFIX + key, LobbyService.MetadataValue.text(value));
    }
    private static String text(Map<String, LobbyService.MetadataValue> values, String key) {
        LobbyService.MetadataValue value = values.get(PREFIX + key);
        if (value == null) throw new IllegalArgumentException("missing metadata");
        return value.value();
    }

    @Override public CompletionStage<JoinHandleResponse> createJoinHandle(JoinHandleRequest request) {
        Resolved value = resolved.get(request.listingId());
        if (closed || value == null || value.entry().stale(clock.instant())) return unavailable();
        // Local one-use attempt id, NOT a Steam credential or authorization proof.
        // PublicJoinCoordinator has just refreshed the listing; core still gates entry.
        return CompletableFuture.completedFuture(new JoinHandleResponse(UUID.randomUUID().toString(),
                value.target(), InstantStamp.of(clock.instant().plusSeconds(15))));
    }
    static <T> T unwrap(ApiResult<T> result) {
        if (result.isSuccess()) return result.value().orElseThrow();
        throw new CoreDirectoryException(result.error().orElseThrow());
    }
    private static <T> CompletionStage<T> unavailable() {
        return CompletableFuture.failedFuture(new IllegalStateException("Steam directory operation unavailable"));
    }
    // HTTPS registry writes/receipts are deliberately not used by the native Steam publisher.
    @Override public CompletionStage<ChallengeResponse> requestChallenge(ChallengeRequest request) { return unavailable(); }
    @Override public CompletionStage<PublicationLease> createPublication(CreatePublicationRequest request) { return unavailable(); }
    @Override public CompletionStage<PublicationLease> heartbeat(String id, LeaseMutationRequest request) { return unavailable(); }
    @Override public CompletionStage<PublicationLease> updatePublication(String id, LeaseMutationRequest request) { return unavailable(); }
    @Override public CompletionStage<Void> deletePublication(String id, LeaseMutationRequest request) { return unavailable(); }
    @Override public CompletionStage<Void> report(ReportRequest request) { return unavailable(); }

    record Resolved(PublicServerEntry entry, String target) { }
}
