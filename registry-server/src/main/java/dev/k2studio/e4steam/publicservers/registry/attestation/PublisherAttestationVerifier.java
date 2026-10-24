package dev.k2studio.e4steam.publicservers.registry.attestation;

import dev.k2studio.e4steam.publicservers.contract.PublicServerType;

import java.time.Instant;
import java.util.Objects;

/**
 * Pluggable trust boundary for publisher assertions.
 *
 * <p>A production implementation must validate an assertion created by e4steam core and must
 * never expose a raw Steam auth ticket to this process or to the addon. The bundled development
 * implementation is deliberately HMAC-based and is not a Steam identity verifier.</p>
 */
public interface PublisherAttestationVerifier {
    VerifiedPublisher verify(Challenge challenge, String assertion, Instant now)
            throws VerificationException;

    default boolean available() {
        return true;
    }

    record Challenge(String challengeId, String nonce, String audience, String action,
                     PublicServerType type, Instant expiresAt) {
        public Challenge {
            Objects.requireNonNull(challengeId, "challengeId");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record VerifiedPublisher(String ownerRef, Authority authority) {
        public VerifiedPublisher {
            Objects.requireNonNull(ownerRef, "ownerRef");
            Objects.requireNonNull(authority, "authority");
        }
    }

    enum Authority {
        STEAM_USER,
        DEDICATED_SERVER
    }

    final class VerificationException extends Exception {
        private static final long serialVersionUID = 1L;

        public VerificationException() {
            super("publisher assertion rejected");
        }
    }
}
