# Direct Steam catalog (beta.10)

## Scope

Default configuration now uses the installed Steam client, App ID 480 and the existing Addon API 1.1.0 LobbyService. No HTTP request, VPS, API key or downloaded code is involved. `https://steamcommunity.com` is only the logical scope used by existing directory DTOs, not a registry endpoint.

The matching core development build implements previously unavailable create/search/metadata/leave operations. This does not change the public Java API or the game transport. A core version number alone does not guarantee these operations exist.

## Ownership and lifecycle

- Core owns native matchmaking handles, one scope per addon, and executes them on its existing Steam worker.
- Scoped capabilities guard create, search and metadata writes. Addons may write only their namespace.
- Completion is dispatched away from native callbacks, with bounded queues and timeouts.
- The addon owns a separate public advertisement lobby. Its two Steam member slots are unrelated to the world's configured 2–64 Minecraft player slots; guests join the game target, not the advertisement lobby.
- Only an active integrated host using this addon's confirmed custom access mode can receive a publication target.
- The publisher refreshes every 10 seconds, stops on session closure, and disposes late creation results. Catalog entries expire after 45 seconds, including after a crash.

## Trust boundary

Advertisements contain the public title, Minecraft/loader/core/addon versions, wire version, player count, capacity, update time and a non-secret opaque target reference. They contain no Steam auth ticket or credential-bearing join address. Steam lobby IDs and host names are publicly discoverable in this mode.

Shared App ID advertisements can be spoofed. Catalog text is never authorization. The local short-lived join handle is a replay/attempt identifier, not an attestation or credential. Steam lobby ownership, the core handshake, authentication, capacity and access policy still gate the connection. No production HTTP attestation receipt is fabricated.

## Current limitations

- Native Steam mode publishes integrated worlds only. A headless dedicated server cannot use this client matchmaking implementation. Its catalog path remains unfinished.
- One bounded search returns up to 50 entries worldwide; Steam lobby search has no cursor here.
- Remote reports are unavailable; local hiding/blocking remains available.
- Build/unit results do not establish two-account discovery and joining. Test those separately.

## Two-account check

1. Install the new core and beta.10 addon on both PCs with matching Minecraft versions.
2. Host: choose Public Server, open and confirm. Guest: open the catalog and wait for its automatic refresh (every 10 seconds).
3. Check title, versions and player count (host alone is 1/configured capacity).
4. Join, disconnect, refresh and join again.
5. Close the world; its row should disappear on refresh (or expire within 45 seconds after an abnormal shutdown).
6. Reopen with friends-only/invite-only access; it must not appear publicly.
7. Reopen as public and verify a fresh row and successful join.
8. Cancel joining and close/reopen the browser; no pending action may connect later.

Safe log codes: `steam_directory_enabled`, `steam_publication_active`, `steam_publication_waiting`, `steam_publication_failed`. Do not post raw Steam join addresses with logs.
