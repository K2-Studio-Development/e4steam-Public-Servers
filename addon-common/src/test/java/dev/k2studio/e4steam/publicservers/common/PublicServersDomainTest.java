package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.DirectoryPage;
import dev.k2studio.e4steam.publicservers.contract.DirectoryProtocol;
import dev.k2studio.e4steam.publicservers.contract.InstantStamp;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.testsupport.FakePublicDirectoryClient;
import dev.k2studio.e4steam.publicservers.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PublicServersDomainTest {
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final URI ORIGIN = URI.create("https://registry.example");

    @Test void compatibilityAndSortingPreferJoinableWorlds() {
        ClientEnvironment local = environment();
        CompatibilityEvaluator evaluator = new CompatibilityEvaluator();
        PublicServerEntry joinable = entry("listing.joinable", "Alpha", PublicServerType.INTEGRATED_WORLD,
                "26.2", "fabric", 2, 8, NOW.plusSeconds(60));
        PublicServerEntry full = entry("listing.full", "Beta", PublicServerType.DEDICATED_SERVER,
                "26.2", "fabric", 8, 8, NOW.plusSeconds(60));
        PublicServerEntry incompatible = entry("listing.old", "Gamma", PublicServerType.DEDICATED_SERVER,
                "1.21.1", "neoforge", 0, 20, NOW.plusSeconds(60));
        PublicServersSnapshot snapshot = new PublicServersSnapshot(PublicServersSnapshot.State.READY,
                List.of(item(full, evaluator, local), item(incompatible, evaluator, local),
                        item(joinable, evaluator, local)), "", NOW, "");
        assertEquals(List.of("listing.joinable", "listing.full", "listing.old"), snapshot.items().stream()
                .map(value -> value.entry().listingId()).toList());
        assertTrue(snapshot.items().get(0).compatibility().joinable());
        assertFalse(snapshot.items().get(1).compatibility().joinable());
    }

    @Test void filterSearchesMetadataAndSeparatesIntegratedAndDedicated() {
        PublicServerEntry integrated = entry("listing.integrated", "Русский мир",
                PublicServerType.INTEGRATED_WORLD, "26.2", "fabric", 1, 8, NOW.plusSeconds(60));
        Compatibility compatible = Compatibility.compatibleResult();
        PublicServersFilter filter = new PublicServersFilter("русский", PublicServerType.INTEGRATED_WORLD,
                true, "26.2", "fabric", true, java.util.Set.of(), java.util.Set.of());
        assertTrue(filter.matches(integrated, compatible));
        assertFalse(new PublicServersFilter("", PublicServerType.DEDICATED_SERVER, false,
                "", "", false, java.util.Set.of(), java.util.Set.of()).matches(integrated, compatible));
    }

    @Test void cacheExpiresAndNeverExposesStaleListings() {
        DirectoryCache cache = new DirectoryCache(2, Duration.ofSeconds(10));
        PublicServerEntry entry = entry("listing.cache", "Cache", PublicServerType.INTEGRATED_WORLD,
                "26.2", "fabric", 1, 8, NOW.plusSeconds(60));
        cache.put(new DirectoryPage(List.of(entry), "", InstantStamp.of(NOW)), NOW);
        assertEquals(1, cache.size());
        assertTrue(cache.snapshot(NOW.plusSeconds(11)).isEmpty());
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.snapshot(NOW));
    }

    @Test void replacementClosesOldSessionAndPendingRefreshCannotResurrectIt() {
        MutableClock clock = new MutableClock(NOW);
        PublicServersRuntime runtime = PublicServersRuntime.install(new UnavailableCoreDirectoryGateway(), clock);
        FakePublicDirectoryClient first = new FakePublicDirectoryClient(ORIGIN);
        CompletableFuture<DirectoryPage> pending = first.enqueuePendingCatalog();
        runtime.configure(config(), environment(), first);
        CompletableFuture<PublicServersSnapshot> oldRefresh = runtime.refresh().toCompletableFuture();
        FakePublicDirectoryClient second = new FakePublicDirectoryClient(ORIGIN);
        second.enqueueCatalog(new DirectoryPage(List.of(), "", InstantStamp.of(NOW)));
        runtime.configure(config(), environment(), second);
        assertTrue(first.isClosed());
        pending.complete(new DirectoryPage(List.of(), "", InstantStamp.of(NOW)));
        assertEquals(PublicServersSnapshot.State.CLOSED, oldRefresh.join().state());
        assertEquals(PublicServersSnapshot.State.EMPTY, runtime.refresh().toCompletableFuture().join().state());
        runtime.close();
        assertTrue(second.isClosed());
        assertTrue(PublicServersRuntime.current().isEmpty());
    }

    @Test void noEndpointMeansNotConfiguredAndNoBackgroundSession() {
        PublicServersRuntime runtime = PublicServersRuntime.install(new UnavailableCoreDirectoryGateway(),
                new MutableClock(NOW));
        assertEquals(PublicServersSnapshot.State.NOT_CONFIGURED, runtime.clientSnapshot().state());
        assertEquals(PublicServersSnapshot.State.NOT_CONFIGURED,
                runtime.refresh().toCompletableFuture().join().state());
        runtime.close();
    }

    @Test void productionConfigurationRejectsPlainHttpAndEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class, () -> PublicServersConfig.builder()
                .registryOrigin(URI.create("http://registry.example")).build());
        assertThrows(IllegalArgumentException.class, () -> PublicServersConfig.builder()
                .registryOrigin(URI.create("https://user:pass@registry.example")).build());
        assertTrue(PublicServersConfig.builder().registryOrigin(ORIGIN).build().configured());
    }

    @Test void publicWorldNameUsesCreatorMinecraftNickname() {
        assertEquals("Kamilchik server", ServerDisplayName.fromCreator("Kamilchik"));
        assertEquals(ServerDisplayName.FALLBACK, ServerDisplayName.fromCreator(""));
        assertEquals(ServerDisplayName.FALLBACK,
                ServerDisplayName.fromCreator("unsafe https://example.test"));
    }

    private static PublicServersSnapshot.Item item(PublicServerEntry entry,
                                                    CompatibilityEvaluator evaluator,
                                                    ClientEnvironment local) {
        return new PublicServersSnapshot.Item(entry, evaluator.evaluate(entry, local, NOW));
    }

    private static PublicServersConfig config() {
        return PublicServersConfig.builder().registryOrigin(ORIGIN).pageSize(10).maximumPages(2).build();
    }

    private static ClientEnvironment environment() {
        return new ClientEnvironment("26.2", "fabric", "0.3.2", 1,
                DirectoryProtocol.VERSION, Map.of());
    }

    private static PublicServerEntry entry(String id, String name, PublicServerType type,
                                           String minecraft, String loader, int players, int capacity,
                                           Instant expires) {
        ListingMetadata metadata = new ListingMetadata(DirectoryProtocol.METADATA_SCHEMA, name, "Survival",
                List.of("survival"), minecraft, loader, "0.3.2", 1, PublicServersAddon.VERSION,
                DirectoryProtocol.VERSION, players, capacity, "public", Map.of());
        return new PublicServerEntry(id, 1, type, metadata, NOW, expires, "owner." + id.substring(8));
    }
}
