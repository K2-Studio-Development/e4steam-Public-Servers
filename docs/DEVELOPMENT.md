# Development

Requirements: JDK 17 and JDK 25, plus network access for the first Gradle dependency resolution. Gradle toolchains select Java 17 for the Java 16/17 ports and Java 25 for Minecraft 26.x and the registry backend.

```powershell
.\gradlew.bat verification --no-configuration-cache
.\gradlew.bat releaseArtifactAudit --no-configuration-cache
.\gradlew.bat :registry-server:backendArtifact --no-configuration-cache
```

For local registry-contract and catalog browsing tests, provide a base64 key of at least 32 bytes to the registry as `E4STEAM_REGISTRY_DEV_HMAC_KEY`, start the backend, and launch Minecraft with:

```text
-De4steam.publicServers.registryOrigin=http://127.0.0.1:8090
-De4steam.publicServers.allowDevelopmentLoopback=true
-De4steam.publicServers.publishIntegrated=true
-De4steam.publicDirectory.allowDevelopmentLoopbackAttestation=true
-De4steam.publicDirectory.developmentKeyFile=config/e4steam-public-servers-dev.key
```

The key file contains the same URL-safe base64 key supplied to the registry.
Keep it outside source control and restrict it to the local test account. The
development verifier proves only possession of this key. It is not a
production Steam identity verifier, accepts only a loopback HTTP origin, and
must never be exposed to the internet.

The `0.3.2` core supports this explicitly enabled loopback test path while
continuing to fail closed for production HTTPS attestations until a real Steam
publisher verifier is configured.
