# Architecture

The project has a loader-neutral backend/common layer and seven modern runtime
variants split at actual Minecraft GUI and bytecode boundaries:

- `registry-contract`: immutable wire DTOs, strict validation and JSON codec;
- `registry-client`: bounded asynchronous HTTPS client;
- `registry-server`: registry state machine, HTTP boundary and SQLite storage;
- `addon-common`: catalog snapshots, compatibility, publication leases and e4steam API integration;
- `addon-portable`: Java 16/17 native-widget screen and narrow reflection compatibility boundary;
- `addon-fabric-legacy17` / `addon-forge-legacy17`: Minecraft 1.17-era artifacts;
- `addon-fabric-classic` / `addon-forge-classic` / `addon-neoforge-classic`: Minecraft 1.19–1.21-era artifacts;
- `addon-fabric` / `addon-neoforge`: Minecraft 26.x artifacts with the full Friends-style textured GUI;
- `test-support`: deterministic clock and scripted no-network registry client.

The Java 16/17 ports are built in the isolated `ports` Gradle build. This keeps
Architectury Loom's obfuscated mappings separate from the named Minecraft 26.x
Fabric/NeoForge toolchain. `releaseArtifactAudit` runs both builds and collects
exactly seven distributable JARs.

Minecraft code exists only in loader modules. Steam transport, authentication and connection ownership remain in e4steam core. The addon receives only credential-free target references and sends one-use join handles back through `PublicDirectoryService`.

```text
Minecraft GUI -> addon-common snapshot -> registry-client -> HTTPS registry
                         |                        |
                         +-> Addon API 1.1 <------+ attestation/join handles
                                   |
                              e4steam core -> Steam transport
```

The registry never connects players. It lists sanitized metadata and issues bounded join handles. The core performs the actual Steam/Minecraft connection and all mandatory admission gates.
