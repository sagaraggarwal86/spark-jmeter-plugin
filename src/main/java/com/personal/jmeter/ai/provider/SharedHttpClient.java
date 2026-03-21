package com.personal.jmeter.ai.provider;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Single shared {@link HttpClient} singleton for the AI reporting subsystem.
 *
 * <p>Replaces the two separate singletons that previously existed in
 * {@link AiReportService} (30 s connect timeout) and
 * {@link AiProviderRegistry} (10 s connect timeout).  A single client with a
 * balanced 15 s connect timeout eliminates duplication while preserving the
 * per-request timeout supplied by {@link AiProviderConfig#timeoutSeconds}.</p>
 *
 * <p>Reusing one client allows connection-pool sharing across report
 * generation and provider pings, avoiding redundant TLS handshakes.</p>
 * @since 4.6.0
 */
public final class SharedHttpClient {

    /**
     * Balanced connect timeout — long enough for high-latency corporate
     * networks (previously 30 s in AiReportService), short enough for
     * responsive ping feedback (previously 10 s in AiProviderRegistry).
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Long-lived singleton — thread-safe and connection-pooled.
     */
    private static final HttpClient INSTANCE = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private SharedHttpClient() { /* static utility — not instantiable */ }

    /**
     * Returns the shared {@link HttpClient} singleton.
     *
     * @return shared client instance; never null
     */
    public static HttpClient get() {
        return INSTANCE;
    }
}
