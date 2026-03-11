package com.personal.jmeter.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Calls an OpenAI-compatible AI API and returns the generated report in Markdown format.
 *
 * <p>The provider, model, endpoint URL, and request parameters are supplied via
 * {@link AiProviderConfig}, enabling any provider registered in
 * {@code ai-reporter.properties} to be used without code changes.</p>
 *
 * <p>The {@link SharedHttpClient} provides a shared singleton — it manages a connection pool
 * internally and must not be recreated per request.  The per-request
 * {@link HttpRequest} timeout is taken from {@link AiProviderConfig#timeoutSeconds};
 * the client-level {@code connectTimeout} is a fixed generous value.</p>
 *
 * <p>Retry behaviour: HTTP 429 and HTTP 5xx responses trigger up to
 * {@value #MAX_ATTEMPTS} total attempts with a {@value #RETRY_DELAY_MS} ms
 * minimum delay between retries. Non-retryable 4xx errors are thrown immediately.</p>
 */
public class AiReportService {

    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);

    /** Total number of attempts (1 initial + 2 retries). */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Minimum delay in milliseconds between retry attempts. */
    private static final long RETRY_DELAY_MS = 2_000L;

    private final AiProviderConfig config;

    /**
     * Constructs the service for the given provider configuration.
     *
     * @param config fully resolved provider config; must not be null
     */
    public AiReportService(AiProviderConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    // ─────────────────────────────────────────────────────────────
    // Report generation
    // ─────────────────────────────────────────────────────────────

    /**
     * Sends the two-part prompt to the configured AI provider and returns the
     * AI-generated Markdown report.
     *
     * @param promptContent fully assembled analysis prompt; must not be null
     * @return AI-generated report text in Markdown
     * @throws AiServiceException       if the API returns an error, empty response,
     *                                   or all retry attempts are exhausted
     * @throws IOException              on network timeout or interrupted request
     * @throws IllegalArgumentException if promptContent is null
     */
    public String generateReport(PromptContent promptContent) throws IOException {
        Objects.requireNonNull(promptContent, "promptContent must not be null");
        log.info("generateReport: provider={} model={} systemLength={} userLength={}",
                config.providerKey, config.model,
                promptContent.systemPrompt().length(), promptContent.userMessage().length());
        return callProvider(promptContent);
    }

    // ─────────────────────────────────────────────────────────────
    // Private implementation
    // ─────────────────────────────────────────────────────────────

    private String callProvider(PromptContent content) throws IOException {
        final String requestBody = buildRequestBody(content);
        AiServiceException lastEx = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response = sendRequest(requestBody);
            int status = response.statusCode();

            if (status == 200) {
                return extractAndValidateContent(response.body());
            }

            boolean retryable = (status == 429 || (status >= 500 && status < 600));
            lastEx = new AiServiceException(String.format(
                    "%s API returned HTTP %d. Body: %s", config.displayName, status, response.body()));

            if (!retryable || attempt == MAX_ATTEMPTS) {
                throw lastEx;
            }

            log.warn("callProvider: attempt {}/{} failed with HTTP {} for provider={}. Retrying in {}ms.",
                    attempt, MAX_ATTEMPTS, status, config.providerKey, RETRY_DELAY_MS);
            sleepBeforeRetry();
        }

        throw Objects.requireNonNull(lastEx);
    }

    private HttpResponse<String> sendRequest(String requestBody) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            return SharedHttpClient.get().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("sendRequest: request interrupted. provider={} reason={}",
                    config.providerKey, e.getMessage(), e);
            throw new AiServiceException(config.displayName + " API request was interrupted", e);
        }
    }

    private void sleepBeforeRetry() throws AiServiceException {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(config.displayName + " API retry sleep interrupted", e);
        }
    }

    private String buildRequestBody(PromptContent content) {
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", content.systemPrompt());

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", content.userMessage());

        JsonArray messages = new JsonArray();
        messages.add(systemMsg);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("temperature", config.temperature);
        body.addProperty("max_tokens", config.maxTokens);
        body.add("messages", messages);

        return body.toString();
    }

    private String extractAndValidateContent(String responseBody) throws AiServiceException {
        final String aiText;
        try {
            aiText = JsonParser.parseString(responseBody)
                    .getAsJsonObject()
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (JsonParseException | IllegalStateException | IndexOutOfBoundsException e) {
            log.error("extractAndValidateContent: failed to parse response. provider={} reason={}",
                    config.providerKey, e.getMessage(), e);
            throw new AiServiceException("Failed to parse " + config.displayName
                    + " API response: " + e.getMessage(), e);
        }

        if (aiText == null || aiText.isBlank()) {
            throw new AiServiceException(
                    config.displayName + " API returned an empty response. "
                            + "Check model ID, API key, and response parsing format. "
                            + "No file was written.");
        }

        return aiText;
    }
}