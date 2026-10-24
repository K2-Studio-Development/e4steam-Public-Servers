# Security

- No raw Steam auth ticket, lobby secret, login token, password, cookie or public IP belongs in the registry protocol.
- The addon does not call Steamworks, native libraries, reflection or internal e4steam classes.
- Every publication uses a challenge, short-lived attestation receipt and renewable lease.
- Receipts, lease proofs and join handles redact their values from `toString()`.
- Join handles are one-use and time-limited; core admission still checks authentication, generation, protocol, capacity, bans and replay.
- Integrated worlds remain limited to host plus seven guests.
- HTTP clients require HTTPS. Development HTTP is limited to an explicit loopback origin.
- The backend binds only to loopback and must sit behind a maintained TLS reverse proxy.
- Executors, queues, request sizes, pages, filters and listener counts are bounded.
- Production publication must remain disabled until a real e4steam-to-registry verifier and production origin are configured.

Do not place keys in Git, command examples, screenshots or logs. Rotate the privacy key only with an explicit migration plan because it changes report fingerprints.
