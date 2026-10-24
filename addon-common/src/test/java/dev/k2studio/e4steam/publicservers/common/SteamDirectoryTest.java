package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.*;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import link.e4steam.api.ApiResult;
import link.e4steam.api.lobby.LobbyService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

final class SteamDirectoryTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static ListingMetadata metadata() {
        return new ListingMetadata(DirectoryProtocol.METADATA_SCHEMA, "Alex server", "", List.of("modded"),
                "1.21.11", "fabric", "0.3.2-beta.1", 1, PublicServersAddon.VERSION,
                DirectoryProtocol.VERSION, 1, 8, PublicAccessModeProvider.MODE_ID.value(), Map.of());
    }
    private static LobbyService.LobbySnapshot lobby(LobbyService.Metadata data) {
        return new LobbyService.LobbySnapshot(new LobbyService.LobbyId("123"), LobbyService.Visibility.PUBLIC,
                2, List.of(), data);
    }
    @Test void advertisedCapacityIsWorldCapacityNotSidecarLobbyCapacity() {
        var decoded = SteamDirectoryClient.decode(lobby(SteamDirectoryClient.encode(metadata(), "iw_456", NOW)), NOW);
        assertEquals(8, decoded.entry().metadata().capacity());
        assertEquals(1, decoded.entry().metadata().players());
        assertEquals("Alex server", decoded.entry().metadata().name());
        assertEquals("iw_456", decoded.target());
    }
    @Test void staleFutureAndWrongCapacityAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SteamDirectoryClient.decode(
                lobby(SteamDirectoryClient.encode(metadata(), "iw_456", NOW.minusSeconds(45))), NOW));
        assertThrows(IllegalArgumentException.class, () -> SteamDirectoryClient.decode(
                lobby(SteamDirectoryClient.encode(metadata(), "iw_456", NOW.plusSeconds(16))), NOW));
        var values = new HashMap<>(SteamDirectoryClient.encode(metadata(), "iw_456", NOW).values());
        values.put(SteamDirectoryClient.PREFIX + "capacity", LobbyService.MetadataValue.text("65"));
        assertThrows(IllegalArgumentException.class, () -> SteamDirectoryClient.decode(lobby(new LobbyService.Metadata(values)), NOW));
    }
    @Test void configuredLanCapacitiesRemainVisible() {
        for (int capacity : new int[]{2, 8, 16, 32, 64}) {
            var values = new HashMap<>(SteamDirectoryClient.encode(metadata(), "iw_456", NOW).values());
            values.put(SteamDirectoryClient.PREFIX + "capacity", LobbyService.MetadataValue.text(Integer.toString(capacity)));
            var decoded = SteamDirectoryClient.decode(lobby(new LobbyService.Metadata(values)), NOW);
            assertEquals(capacity, decoded.entry().metadata().capacity());
            assertDoesNotThrow(() -> MetadataValidator.validate(PublicServerType.INTEGRATED_WORLD, decoded.entry().metadata()));
            assertTrue(PublicServersAddon.validWorldCapacity(capacity));
        }
        assertFalse(PublicServersAddon.validWorldCapacity(1));
        assertFalse(PublicServersAddon.validWorldCapacity(65));
    }
    @Test void malformedForeignEntryDoesNotHideGoodWorldsAndClosedClientCannotJoin() {
        FakeLobbies lobbies = new FakeLobbies();
        var values = new HashMap<>(SteamDirectoryClient.encode(metadata(), "iw_456", NOW).values());
        values.put(SteamDirectoryClient.PREFIX + "updated", LobbyService.MetadataValue.text(Long.toString(Long.MAX_VALUE)));
        lobbies.results = List.of(lobby(new LobbyService.Metadata(values)), lobby(SteamDirectoryClient.encode(metadata(), "iw_456", NOW)));
        SteamDirectoryClient client = new SteamDirectoryClient(lobbies, CLOCK);
        var page = client.catalog(50, "", null).toCompletableFuture().join();
        assertEquals(1, page.entries().size());
        client.close();
        assertThrows(CompletionException.class, () -> client.catalog(50, "", null).toCompletableFuture().join());
    }
    @Test void concurrentRefreshAndJoinShareNativeSearchAndRetryAfterFailure() {
        FakeLobbies lobbies = new FakeLobbies();
        lobbies.searchPending = new CompletableFuture<>();
        SteamDirectoryClient client = new SteamDirectoryClient(lobbies, CLOCK);
        var refresh = client.catalog(50, "", null);
        var join = client.findListing("steam.123", PublicServerType.INTEGRATED_WORLD, 50, 1);
        assertEquals(1, lobbies.searches);
        lobbies.searchPending.complete(ApiResult.success(new LobbyService.SearchPage(
                List.of(lobby(SteamDirectoryClient.encode(metadata(), "iw_456", NOW))), "")));
        assertEquals(1, refresh.toCompletableFuture().join().entries().size());
        assertTrue(join.toCompletableFuture().join().isPresent());
        lobbies.searchPending = new CompletableFuture<>();
        var failed = client.catalog(50, "", null);
        lobbies.searchPending.completeExceptionally(new IllegalStateException("Steam offline"));
        assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join());
        lobbies.searchPending = null;
        client.catalog(50, "", null).toCompletableFuture().join();
        assertEquals(3, lobbies.searches);
        client.close();
    }
    @Test void publicWorldCanBeDiscoveredJoinedAndRemoved() {
        FakeLobbies lobbies = new FakeLobbies();
        Gateway core = new Gateway();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        SteamDirectoryClient client = new SteamDirectoryClient(lobbies, CLOCK);
        var config = PublicServersConfig.builder().registryOrigin(SteamDirectoryClient.ORIGIN)
                .steamDirectory(true).integratedPublicationEnabled(true).build();
        var environment = new ClientEnvironment("1.21.11", "fabric", "0.3.2-beta.1", 1,
                DirectoryProtocol.VERSION, Map.of());
        try (SteamWorldPublisher publisher = new SteamWorldPublisher(lobbies, core, CLOCK, timer,
                (level, code, fields) -> ApiResult.success(true));
             PublicJoinCoordinator join = new PublicJoinCoordinator(client, core,
                     new CompatibilityEvaluator(), environment, config, CLOCK, null)) {
            publisher.world(metadata());
            assertEquals(1, lobbies.creates, "Publication starts immediately");
            var advertised = lobby(lobbies.request.metadata());
            lobbies.pending.complete(ApiResult.success(advertised));
            lobbies.results = List.of(advertised);
            assertEquals(1, client.catalog(50, "", null).toCompletableFuture().join().entries().size());
            assertEquals(PublicJoinCoordinator.State.ACTIVE,
                    join.join("steam.123", PublicServerType.INTEGRATED_WORLD).toCompletableFuture().join().state());
            assertEquals("iw_456", core.joinedTarget);
            publisher.stop();
            assertTrue(client.catalog(50, "", null).toCompletableFuture().join().entries().isEmpty());
        } finally { client.close(); timer.shutdownNow(); }
    }
    @Test void lateSearchCannotRepopulateClosedCatalog() {
        FakeLobbies lobbies = new FakeLobbies();
        lobbies.searchPending = new CompletableFuture<>();
        SteamDirectoryClient client = new SteamDirectoryClient(lobbies, CLOCK);
        var pending = client.catalog(50, "", null);
        client.close();
        lobbies.searchPending.complete(ApiResult.success(new LobbyService.SearchPage(
                List.of(lobby(SteamDirectoryClient.encode(metadata(), "iw_456", NOW))), "")));
        assertThrows(CompletionException.class, () -> pending.toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> client.createJoinHandle(new JoinHandleRequest("steam.123")).toCompletableFuture().join());
    }
    @Test void missingPublicTargetNeverCreatesAdvertisement() {
        FakeLobbies lobbies = new FakeLobbies();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try (SteamWorldPublisher publisher = new SteamWorldPublisher(lobbies, new UnavailableCoreDirectoryGateway(),
                CLOCK, timer, (level, code, fields) -> ApiResult.success(true))) {
            publisher.world(metadata()); publisher.tick();
            assertEquals(0, lobbies.creates);
        } finally { timer.shutdownNow(); }
    }
    @Test void lateCreateAfterWorldCloseLeavesLobbyAndReopenCanPublish() {
        FakeLobbies lobbies = new FakeLobbies();
        Gateway core = new Gateway();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try (SteamWorldPublisher publisher = new SteamWorldPublisher(lobbies, core, CLOCK, timer,
                (level, code, fields) -> ApiResult.success(true))) {
            publisher.world(metadata()); publisher.tick();
            assertEquals(1, lobbies.creates);
            publisher.stop();
            lobbies.pending.complete(ApiResult.success(lobby(lobbies.request.metadata())));
            assertEquals(1, lobbies.leaves);
            publisher.world(metadata()); publisher.tick();
            assertEquals(2, lobbies.creates);
            lobbies.pending.complete(ApiResult.success(lobby(lobbies.request.metadata())));
            publisher.tick();
            assertEquals(1, lobbies.updates);
            core.available = false;
            publisher.tick();
            assertEquals(2, lobbies.leaves);
        } finally { timer.shutdownNow(); }
    }
    @Test void repeatedTickDoesNotDuplicatePendingPublication() {
        FakeLobbies lobbies = new FakeLobbies();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try (SteamWorldPublisher publisher = new SteamWorldPublisher(lobbies, new Gateway(), CLOCK, timer,
                (level, code, fields) -> ApiResult.success(true))) {
            publisher.world(metadata()); publisher.tick(); publisher.tick();
            assertEquals(1, lobbies.creates);
        } finally { timer.shutdownNow(); }
        lobbies.pending.complete(ApiResult.success(lobby(lobbies.request.metadata())));
        assertEquals(1, lobbies.leaves);
    }
    private static final class FakeLobbies implements LobbyService {
        int creates, updates, leaves, searches;
        CompletableFuture<ApiResult<SearchPage>> searchPending;
        CreateRequest request;
        CompletableFuture<ApiResult<LobbySnapshot>> pending;
        List<LobbySnapshot> results = List.of();
        public CompletionStage<ApiResult<LobbySnapshot>> create(CreateRequest value) {
            creates++; request = value; pending = new CompletableFuture<>(); return pending;
        }
        public CompletionStage<ApiResult<Boolean>> leave(LobbyId id) { leaves++; results = List.of(); return CompletableFuture.completedFuture(ApiResult.success(true)); }
        public CompletionStage<ApiResult<LobbySnapshot>> updateMetadata(LobbyId id, Metadata data) {
            updates++; return CompletableFuture.completedFuture(ApiResult.success(lobby(data)));
        }
        public CompletionStage<ApiResult<SearchPage>> search(SearchQuery query) {
            searches++;
            return searchPending != null ? searchPending : CompletableFuture.completedFuture(ApiResult.success(new SearchPage(results, "")));
        }
        public CompletionStage<ApiResult<LobbySnapshot>> join(LobbyId id) { throw new AssertionError("Must join through core directory"); }
    }
    private static final class Gateway implements CoreDirectoryGateway {
        boolean available = true;
        String joinedTarget;
        public Availability availability() { return Availability.AVAILABLE; }
        public CompletionStage<PublicationTarget> publicationTarget(PublicServerType type, URI origin) {
            return available ? CompletableFuture.completedFuture(new PublicationTarget("iw_456", 1))
                    : CompletableFuture.failedFuture(new IllegalStateException("World closed"));
        }
        public CompletionStage<AttestationReceipt> attest(AttestationRequest request) { throw new AssertionError("No forged registry attestation"); }
        public CompletionStage<JoinOperation> join(String target, String handle) { joinedTarget = target; return CompletableFuture.completedFuture(new JoinOperation("join.1", JoinState.ACTIVE, "")); }
        public CompletionStage<JoinOperation> cancel(String id) { throw new AssertionError(); }
        public JoinOperation joinSnapshot(String id) { throw new AssertionError(); }
    }
}
