package dev.k2studio.e4steam.publicservers.registry.attestation;

import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import dev.k2studio.e4steam.publicservers.contract.TextPolicy;
import dev.k2studio.e4steam.publicservers.registry.SecureTokens;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Explicit local-development verifier. It does not claim to prove a Steam identity. */
public final class DevelopmentAttestationVerifier implements PublisherAttestationVerifier {
    private static final long MAX_ASSERTION_LIFETIME_SECONDS = 120;
    private final byte[] key;

    public DevelopmentAttestationVerifier(byte[] key) {
        if (key == null || key.length < 32) throw new IllegalArgumentException("development key is too short");
        this.key = key.clone();
    }

    @Override
    public VerifiedPublisher verify(Challenge challenge, String assertion, Instant now)
            throws VerificationException {
        try {
            if (assertion == null || assertion.length() > 768) throw new IllegalArgumentException();
            String[] parts = assertion.split("\\.", -1);
            if (parts.length != 5 || !"dev1".equals(parts[0])) throw new IllegalArgumentException();
            Authority authority = switch (parts[1]) {
                case "user" -> Authority.STEAM_USER;
                case "server" -> Authority.DEDICATED_SERVER;
                default -> throw new IllegalArgumentException();
            };
            String ownerRef = TextPolicy.opaque(new String(Base64.getUrlDecoder().decode(parts[2]),
                    StandardCharsets.UTF_8), "ownerRef", 96);
            long expiresAtMillis = Long.parseLong(parts[3]);
            Instant expiresAt = Instant.ofEpochMilli(expiresAtMillis);
            if (!expiresAt.isAfter(now) || expiresAt.isAfter(challenge.expiresAt())
                    || expiresAt.isAfter(now.plusSeconds(MAX_ASSERTION_LIFETIME_SECONDS))) {
                throw new IllegalArgumentException();
            }
            if ((challenge.type() == PublicServerType.INTEGRATED_WORLD && authority != Authority.STEAM_USER)
                    || (challenge.type() == PublicServerType.DEDICATED_SERVER
                    && authority != Authority.DEDICATED_SERVER)) {
                throw new IllegalArgumentException();
            }
            String canonical = canonical(challenge, parts[1], parts[2], expiresAtMillis);
            byte[] supplied = SecureTokens.decodeBase64(parts[4], 64);
            if (!SecureTokens.equal(SecureTokens.hmac(key, canonical), supplied)) {
                throw new IllegalArgumentException();
            }
            return new VerifiedPublisher(ownerRef, authority);
        } catch (RuntimeException rejected) {
            throw new VerificationException();
        }
    }

    /** Helper for local tools and tests; callers must keep the development key out of logs. */
    public static String sign(byte[] key, Challenge challenge, VerifiedPublisher publisher,
                              Instant expiresAt) {
        if (expiresAt == null || !expiresAt.isAfter(Instant.EPOCH)) {
            throw new IllegalArgumentException("invalid assertion expiry");
        }
        String authority = publisher.authority() == Authority.STEAM_USER ? "user" : "server";
        String owner = Base64.getUrlEncoder().withoutPadding().encodeToString(
                publisher.ownerRef().getBytes(StandardCharsets.UTF_8));
        long expiry = expiresAt.toEpochMilli();
        String canonical = canonical(challenge, authority, owner, expiry);
        return "dev1." + authority + '.' + owner + '.' + expiry + '.'
                + SecureTokens.base64(SecureTokens.hmac(key, canonical));
    }

    private static String canonical(Challenge challenge, String authority, String owner,
                                    long expiresAtMillis) {
        return "e4steam-registry-dev-attestation-v1\n"
                + challenge.challengeId() + '\n'
                + challenge.nonce() + '\n'
                + challenge.audience() + '\n'
                + challenge.action() + '\n'
                + challenge.type().name() + '\n'
                + authority + '\n'
                + owner + '\n'
                + expiresAtMillis;
    }
}
