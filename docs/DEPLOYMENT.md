# Registry deployment

Build a reproducible distribution with:

```powershell
.\gradlew.bat :registry-server:installDist --no-configuration-cache
```

Run `registry-server/build/install/registry-server/bin/registry-server` under a restricted service account. The Java process must bind to loopback. Put a maintained HTTPS reverse proxy in front of it, restrict request sizes/timeouts there as well, and persist only the configured SQLite data directory.

Important environment variables:

- `E4STEAM_REGISTRY_BIND` (loopback only) and `E4STEAM_REGISTRY_PORT`;
- `E4STEAM_REGISTRY_DATABASE`;
- `E4STEAM_REGISTRY_AUDIENCE` matching the public HTTPS origin policy;
- `E4STEAM_REGISTRY_PRIVACY_KEY`, base64 and at least 32 bytes;
- `E4STEAM_REGISTRY_MODERATOR_TOKEN`, at least 32 characters;
- worker, queue and timeout bounds documented in `RegistryServerConfig`.

`E4STEAM_REGISTRY_DEV_HMAC_KEY` is forbidden in production. The bundled production fallback rejects every attestation; publication cannot be enabled until a real verifier is supplied and reviewed. No deployment is performed by the build.
