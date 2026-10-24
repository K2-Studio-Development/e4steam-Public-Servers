package dev.k2studio.e4steam.publicservers.common;

import link.e4steam.api.access.AccessService;

import java.util.concurrent.CompletableFuture;

/** Public-world mode evaluated only after core authentication and mandatory admission gates. */
public final class PublicAccessModeProvider implements AccessService.ConfirmableAccessModeProvider {
    public static final AccessService.AccessModeId MODE_ID =
            new AccessService.AccessModeId("k2studio-e4steam_public_servers:public");

    private final boolean configured;
    private final boolean publicationEnabled;

    public PublicAccessModeProvider(PublicServersConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        configured = config.configured();
        publicationEnabled = config.integratedPublicationEnabled();
    }

    @Override public AccessService.AccessModeId id() { return MODE_ID; }
    @Override public String displayNameKey() { return "e4steam_public_servers.access.public"; }
    @Override public String confirmationTitleKey() {
        return configured && publicationEnabled ? "e4steam_public_servers.access.confirm.title"
                : "e4steam_public_servers.access.unavailable.title";
    }
    @Override public String confirmationMessageKey() {
        if (!configured) return "e4steam_public_servers.access.unconfigured.message";
        if (!publicationEnabled) return "e4steam_public_servers.access.disabled.message";
        return "e4steam_public_servers.access.confirm.message";
    }

    @Override
    public AccessService.AdmissionPolicy policy() {
        return context -> {
            if (context == null || !context.coreAuthenticated()) {
                return CompletableFuture.completedFuture(
                        AccessService.AdmissionDecision.deny("core_authentication_required"));
            }
            if (!configured || !publicationEnabled) {
                return CompletableFuture.completedFuture(
                        AccessService.AdmissionDecision.deny("public_directory_not_configured_or_disabled"));
            }
            return CompletableFuture.completedFuture(AccessService.AdmissionDecision.allow());
        };
    }
}
