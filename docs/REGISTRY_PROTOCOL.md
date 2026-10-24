# Registry protocol 1

The HTTP boundary uses UTF-8 JSON, exact field sets and bounded bodies. Requests are limited to 64 KiB and responses to 256 KiB. Unknown fields, unknown enum values, invalid UTF-8 and overlong input fail closed.

Main flows:

1. publisher requests a typed challenge;
2. e4steam core produces a registry-bound assertion;
3. registry verifies it and returns a short-lived receipt;
4. receipt creates one lease and cannot be replayed;
5. publisher heartbeats or updates by lease id, proof and exact revision;
6. clients browse cursor pages without target references;
7. registry issues a short-lived join handle and credential-free target reference;
8. e4steam core consumes the handle once and performs the connection.

Listings expire after their lease TTL even if DELETE is lost. Join handles expire independently and replay is rejected. Integrated worlds must advertise capacity 8.

The bundled HMAC attestation verifier is strictly for local development. Production startup without an installed verifier reports attestation unavailable and rejects publication.
