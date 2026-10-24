package dev.k2studio.e4steam.publicservers.common;

import link.e4steam.api.access.AccessService;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.session.SessionService;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PublicAccessModeProviderTest {
    private static AccessService.AdmissionContext authenticatedPeer() {
        return new AccessService.AdmissionContext(new SessionService.SessionId("session_test", 1),
                new IdentityService.PeerId("peer_test"), new AddonId(PublicServersAddon.ADDON_ID), true);
    }

    @Test void unconfiguredModeExplainsTheProblemAndRejectsJoins() {
        var provider = new PublicAccessModeProvider(PublicServersConfig.notConfigured());
        assertEquals("e4steam_public_servers.access.unconfigured.message", provider.confirmationMessageKey());
        assertEquals(AccessService.AdmissionDecision.Kind.DENY,
                provider.policy().evaluate(authenticatedPeer()).toCompletableFuture().join().kind());
    }

    @Test void configuredDirectoryDoesNotImplyPublicationConsent() {
        var provider = new PublicAccessModeProvider(PublicServersConfig.builder()
                .registryOrigin(URI.create("https://registry.example")).build());
        assertEquals("e4steam_public_servers.access.disabled.message", provider.confirmationMessageKey());
        assertEquals(AccessService.AdmissionDecision.Kind.DENY,
                provider.policy().evaluate(authenticatedPeer()).toCompletableFuture().join().kind());
    }

    @Test void enabledModePreservesAuthenticatedPublicAccess() {
        var provider = new PublicAccessModeProvider(PublicServersConfig.builder()
                .registryOrigin(URI.create("https://registry.example"))
                .integratedPublicationEnabled(true).build());
        assertEquals("e4steam_public_servers.access.confirm.message", provider.confirmationMessageKey());
        assertEquals(AccessService.AdmissionDecision.Kind.ALLOW,
                provider.policy().evaluate(authenticatedPeer()).toCompletableFuture().join().kind());
        assertEquals(AccessService.AdmissionDecision.Kind.DENY,
                provider.policy().evaluate(null).toCompletableFuture().join().kind());
    }
}
