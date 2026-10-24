package dev.k2studio.e4steam.publicservers.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class HttpPublicDirectoryClientTest {
    @Test void productionRequiresCredentialFreeHttpsOrigin() {
        assertThrows(IllegalArgumentException.class, () -> client(
                URI.create("http://registry.example"), HttpPublicDirectoryClient.TransportPolicy.HTTPS_ONLY));
        assertThrows(IllegalArgumentException.class, () -> client(
                URI.create("https://user:pass@registry.example"),
                HttpPublicDirectoryClient.TransportPolicy.HTTPS_ONLY));
        try (HttpPublicDirectoryClient client = client(URI.create("https://REGISTRY.example/"),
                HttpPublicDirectoryClient.TransportPolicy.HTTPS_ONLY)) {
            assertEquals(URI.create("https://registry.example"), client.origin());
        }
    }

    @Test void developmentHttpIsLimitedToLiteralLoopback() {
        assertThrows(IllegalArgumentException.class, () -> client(
                URI.create("http://registry.example"),
                HttpPublicDirectoryClient.TransportPolicy.ALLOW_LOOPBACK_HTTP));
        try (HttpPublicDirectoryClient client = client(URI.create("http://127.0.0.1:8090"),
                HttpPublicDirectoryClient.TransportPolicy.ALLOW_LOOPBACK_HTTP)) {
            assertTrue(client.origin().getHost().equals("127.0.0.1"));
        }
    }

    @Test void closeIsIdempotentAndRejectsNewCalls() {
        HttpPublicDirectoryClient client = client(URI.create("https://registry.example"),
                HttpPublicDirectoryClient.TransportPolicy.HTTPS_ONLY);
        client.close();
        client.close();
        assertTrue(client.isClosed());
        Throwable failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> client.catalog(10, "", null).join()).getCause();
        assertInstanceOf(DirectoryClientException.class, failure);
        assertEquals(DirectoryClientException.Category.CLOSED,
                ((DirectoryClientException) failure).category());
    }

    private static HttpPublicDirectoryClient client(
            URI origin, HttpPublicDirectoryClient.TransportPolicy policy) {
        return new HttpPublicDirectoryClient(origin, Duration.ofSeconds(1), Duration.ofSeconds(1),
                1, 1, policy);
    }
}
