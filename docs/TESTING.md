# Testing checklist

## 0.1.0-beta.13 checks (2026-10-23)

- Added the new icon to Fabric metadata and the NeoForge/Forge logo metadata; all seven packaged variants will be checked for the image.
- Prism profile `e4steam-test-26.3-NeoForge` launched with e4steam `0.3.2-beta.4` and Public Servers `0.1.0-beta.13`; both mods loaded without a mod-loading error.
- The 26.3 LAN form interaction and two-account public-server discovery/join/rejoin still require manual testing.
- Two-account public-server discovery/join/rejoin still requires a second Steam account.

## 0.1.0-beta.11 checks (2026-10-21)

- `test build` passed with Fabric API 0.164.0+26.3 and NeoForge 26.3.0.83; both distributable JAR audits passed.
- Core Fabric/Quilt and NeoForge 26.3 JARs rebuilt successfully; the four core/addon metadata ranges were inspected in the packaged JARs.
- Minecraft client launch and two-account discovery/join/rejoin still need manual testing.

## 0.1.0-beta.10 checks (2026-10-11)

- `verification releaseArtifactAudit` passed on Windows: all seven runtime JARs built and audited, including their dependency on core 0.3.2-beta.1.
- 30 tests passed without skips or failures (20 common, 4 contract, 3 client, 3 registry). The common tests include the public-world publication/discovery/core-join/removal flow, overlapping searches, late callbacks and capacities 2, 8, 16, 32 and 64.
- The opt-in core `SteamAddonLobbyDirectoryNativeTest` passed against the installed Steam client: temporary public lobby creation, actual discovery, metadata update and leave. No Minecraft world was published by this test.
- Two-account Minecraft discovery, join and rejoin have not been verified for beta.10. Historical launch evidence below applies only to its stated build.


## Steam directory preview — 2026-09-28

- Public Servers `0.1.0-beta.9`: `verification releaseArtifactAudit --offline --no-daemon --no-configuration-cache` passed; all seven runtime JARs built and audited. The common suite includes six Steam-catalog regression tests (16 common tests total).
- Core `0.3.3-steam-directory-dev`: common unit tests and Fabric 1.19–1.21.11 build passed. Artifact inspection found no packaging issues; bytecode levels 52/61, nine native libraries.
- Opt-in native Steam test passed (not skipped): temporary lobby creation, actual Steam matchmaking search, metadata update, leave and repeated leave. Test lobby is closed automatically. This is a one-account Steam integration check, not Minecraft multiplayer.
- New core and addon installed into the local SkyBlock Enhanced 1.21.11 profile; previous JARs backed up. Minecraft/world/GUI launch checks have not been repeated for beta.9.
- Second test computer was unreachable on SSH port 22; its JARs were not changed. Two-account discovery/join/rejoin remains unverified.
- Direct Steam dedicated-server publication remains unimplemented. Earlier HTTP-backend checks below do not validate it.

See [the direct Steam checklist](STEAM_DIRECTORY.md#two-account-check). Historical beta.4 launch results below must not be read as beta.9 launch results.

Automated:

```powershell
.\gradlew.bat verification --no-configuration-cache
.\gradlew.bat releaseArtifactAudit --no-configuration-cache
```

This covers strict JSON, metadata safety, configured Steam-world capacity 2–64, compatibility/sorting/filtering, TTL cache, runtime replacement, no-endpoint behavior, challenge/receipt replay, lease revision/expiry, one-use join handles, all seven modern Fabric/Quilt/Forge/NeoForge builds, bytecode limits, absence of Minecraft 26.x GUI links in older JARs and release-directory auditing.

Client launch smoke tests completed on Windows x64 on 2026-09-11:

- Fabric 1.17
- Quilt 1.20.2
- Fabric 26.2
- Forge 1.18.1
- Forge 1.20.2
- NeoForge 1.21.1
- NeoForge 26.2

Each profile loaded e4steam 0.3.2 and Public Servers 0.1.0-beta.4, reached a ready client window after resource/audio initialization and closed without a Public Servers metadata error. These launch checks do not replace the GUI and two-account tests below.

Manual checks still required before release:

- [ ] Fabric 1.17 client starts with the Java 16 artifact.
- [ ] Forge 1.18.1 client starts with the Java 16 artifact.
- [ ] Quilt 1.20.2 client starts and opens the portable catalog.
- [ ] Forge 1.20.2 client starts and opens the portable catalog.
- [ ] NeoForge 1.21.1 client starts and opens the portable catalog.
- [ ] Fabric 26.2 client starts and opens the Friends-style catalog.
- [ ] NeoForge 26.2 client starts and opens the Friends-style catalog.
- [ ] Public Servers button does not overlap vanilla/mod buttons at several GUI scales.
- [ ] Unconfigured build shows Catalog is not configured and sends no registry traffic.
- [ ] Search, filters, selection, scrolling, refresh, reopen and cancellation work.
- [ ] Public Server mode asks for confirmation before first selection.
- [ ] Integrated world publishes only after confirmation and advertises the configured LAN capacity (2–64).
- [ ] `/e4steam stop`, world close, lobby loss and client shutdown remove the lease.
- [ ] Dedicated server publishes only with its explicit config and approved proposal.
- [ ] Real second Steam account joins an integrated world using a fresh handle.
- [ ] Real second Steam account joins a dedicated server using a fresh handle.
- [ ] Replayed/expired handles, full worlds, stale listings and incompatible clients fail safely.
- [ ] TCP gameplay and UDP voice mods continue working.

Do not describe a single-client launch as a completed multiplayer test.

## 2026-10-24 release preparation — beta.18

- All seven runtime targets pass the release artifact audit and automated verification.
- The temporary local status website, resource and HTTP listener have been removed.
- All loader metadata declares Kamilchik and K2 Studio; the shared icon is a decoded 512×512 PNG.
- Forge mod-list crashes on 1.17.1 and 1.18.2 were traced to a slash-containing logoFile path. All Forge/NeoForge metadata now uses a root filename, and the release audit verifies that the file exists.
- The user confirmed LAN background rendering on Forge 1.18.2 and successful world entry on Fabric 1.19.2 and Forge 1.18.2 in the matching core builds. These checks do not cover every mod-list screen or two-account gameplay path.
- The user confirmed that selecting Public Servers in the Forge 1.17.1 Mods screen opens correctly with Kamilchik and K2 Studio credits after the root-logo fix.
- Scrollbar geometry tests cover empty/short lists, overflow, tiny layouts, clamping after filters/resizing and both drag boundaries. Scrollbar changes are confined to catalog UI and do not modify Steam/discovery/join services.

## 2026-10-25 final UI check

The project owner confirmed on Forge 1.17.1 that the catalog fills the window, the background and labels display correctly, and buttons no longer split. The mod-list card was also confirmed to open without a crash and to display the correct authors and icon. Other portable loaders share this catalog implementation; their JARs pass compilation and artifact checks. Drag geometry is covered by automated tests; a live catalog with many real Steam advertisements has not been manually exercised in this run.
