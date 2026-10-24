package dev.k2studio.e4steam.publicservers.registry;

import dev.k2studio.e4steam.publicservers.registry.attestation.DevelopmentAttestationVerifier;
import dev.k2studio.e4steam.publicservers.registry.attestation.PublisherAttestationVerifier;
import dev.k2studio.e4steam.publicservers.registry.attestation.RejectingAttestationVerifier;
import dev.k2studio.e4steam.publicservers.registry.store.SqliteRegistryStore;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local backend entry point. It intentionally has no production domain or deployment side effect. */
public final class RegistryServerMain {
    private RegistryServerMain() {
    }

    public static void main(String[] arguments) {
        try {
            run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.io.IOException startupFailure) {
            /* Deliberately omit exception text: it may contain an operator path or JDBC detail. */
            throw new StartupFailure();
        }
    }

    private static void run() throws java.io.IOException, InterruptedException {
        RegistryServerConfig config = RegistryServerConfig.fromEnvironment(System.getenv());
        byte[] developmentKey = config.developmentHmacKey();
        PublisherAttestationVerifier verifier = developmentKey == null
                ? new RejectingAttestationVerifier()
                : new DevelopmentAttestationVerifier(developmentKey);
        SqliteRegistryStore store = new SqliteRegistryStore(config.databasePath());
        RegistryService service = new RegistryService(store, verifier, Clock.systemUTC(),
                config.audience(), config.challengeTtl(), config.receiptTtl(), config.leaseTtl(),
                config.joinHandleTtl(), config.auditRetention(), config.privacyKey());
        RegistryHttpServer server = null;
        try {
            server = new RegistryHttpServer(new InetSocketAddress(config.bindAddress(), config.port()),
                    service, config.moderatorTokenHash(), config.privacyKey(), config.requestWorkers(),
                    config.requestQueueCapacity(), config.requestTimeout(), config.cleanupInterval());
            RegistryHttpServer runningServer = server;
            CountDownLatch stopped = new CountDownLatch(1);
            AtomicBoolean shutdown = new AtomicBoolean();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (shutdown.compareAndSet(false, true)) {
                    runningServer.close();
                    service.close();
                    stopped.countDown();
                }
            }, "registry-shutdown"));
            runningServer.start();
            System.out.println("e4steam registry started: " + config);
            stopped.await();
        } finally {
            if (server != null) server.close();
            service.close();
        }
    }

    private static final class StartupFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private StartupFailure() {
            super("registry startup failed; inspect safe operator configuration");
        }
    }
}
