package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AiProviderConfig}.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification of
 * constructor validation, field normalisation, and derived URL generation.</p>
 */
@DisplayName("AiProviderConfig")
class AiProviderConfigTest {

    // ── Valid baseline config for reuse ─────────────────────────
    private static AiProviderConfig validConfig() {
        return new AiProviderConfig(
                "groq", "Groq (Free)", "gsk_abc123",
                "llama-3.3-70b", "https://api.groq.com/openai/v1",
                60, 8192, 0.3);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("valid construction")
    class ValidConstruction {

        @Test
        @DisplayName("all fields stored correctly")
        void allFieldsStored() {
            AiProviderConfig cfg = validConfig();
            assertEquals("groq",              cfg.providerKey);
            assertEquals("Groq (Free)",       cfg.displayName);
            assertEquals("gsk_abc123",        cfg.apiKey);
            assertEquals("llama-3.3-70b",     cfg.model);
            assertEquals("https://api.groq.com/openai/v1", cfg.baseUrl);
            assertEquals(60,                  cfg.timeoutSeconds);
            assertEquals(8192,                cfg.maxTokens);
            assertEquals(0.3,                 cfg.temperature, 0.001);
        }

        @Test
        @DisplayName("model is trimmed")
        void modelTrimmed() {
            AiProviderConfig cfg = new AiProviderConfig(
                    "groq", "Groq", "key123",
                    "  llama-3.3  ", "https://api.groq.com/openai/v1",
                    60, 8192, 0.3);
            assertEquals("llama-3.3", cfg.model);
        }

        @Test
        @DisplayName("baseUrl trailing slashes are stripped")
        void baseUrlTrailingSlashStripped() {
            AiProviderConfig cfg = new AiProviderConfig(
                    "groq", "Groq", "key123",
                    "model", "https://api.groq.com/openai/v1///",
                    60, 8192, 0.3);
            assertEquals("https://api.groq.com/openai/v1", cfg.baseUrl);
        }

        @Test
        @DisplayName("baseUrl without trailing slash is unchanged")
        void baseUrlNoTrailingSlash() {
            AiProviderConfig cfg = new AiProviderConfig(
                    "groq", "Groq", "key123",
                    "model", "https://api.groq.com/openai/v1",
                    60, 8192, 0.3);
            assertEquals("https://api.groq.com/openai/v1", cfg.baseUrl);
        }

        @Test
        @DisplayName("baseUrl is trimmed")
        void baseUrlTrimmed() {
            AiProviderConfig cfg = new AiProviderConfig(
                    "groq", "Groq", "key123",
                    "model", "  https://api.groq.com/openai/v1  ",
                    60, 8192, 0.3);
            assertEquals("https://api.groq.com/openai/v1", cfg.baseUrl);
        }

        @Test
        @DisplayName("temperature zero is accepted")
        void temperatureZero() {
            AiProviderConfig cfg = new AiProviderConfig(
                    "groq", "Groq", "key123",
                    "model", "https://api.groq.com",
                    60, 8192, 0.0);
            assertEquals(0.0, cfg.temperature, 0.001);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Constructor validation — null/blank string fields
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("null/blank string rejection")
    class NullBlankRejection {

        @Test
        @DisplayName("null providerKey throws")
        void nullProviderKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig(null, "Name", "key", "model", "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("blank providerKey throws")
        void blankProviderKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("  ", "Name", "key", "model", "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("null displayName throws")
        void nullDisplayName() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", null, "key", "model", "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("blank displayName throws")
        void blankDisplayName() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "  ", "key", "model", "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("null apiKey throws")
        void nullApiKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", null, "model", "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("blank apiKey throws")
        void blankApiKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "  ", "model", "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("null model throws")
        void nullModel() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", null, "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("blank model throws")
        void blankModel() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", "  ", "url", 60, 8192, 0.3));
        }

        @Test
        @DisplayName("null baseUrl throws")
        void nullBaseUrl() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", "model", null, 60, 8192, 0.3));
        }

        @Test
        @DisplayName("blank baseUrl throws")
        void blankBaseUrl() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", "model", "  ", 60, 8192, 0.3));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Constructor validation — numeric fields
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("numeric field rejection")
    class NumericRejection {

        @Test
        @DisplayName("zero timeoutSeconds throws")
        void zeroTimeout() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", "model", "url", 0, 8192, 0.3));
        }

        @Test
        @DisplayName("negative timeoutSeconds throws")
        void negativeTimeout() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", "model", "url", -1, 8192, 0.3));
        }

        @Test
        @DisplayName("zero maxTokens throws")
        void zeroMaxTokens() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", "model", "url", 60, 0, 0.3));
        }

        @Test
        @DisplayName("negative maxTokens throws")
        void negativeMaxTokens() {
            assertThrows(IllegalArgumentException.class, () ->
                    new AiProviderConfig("key", "Name", "key", "model", "url", 60, -1, 0.3));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // chatCompletionsUrl()
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("chatCompletionsUrl()")
    class ChatCompletionsUrl {

        @Test
        @DisplayName("appends /chat/completions to baseUrl")
        void appendsPath() {
            AiProviderConfig cfg = validConfig();
            assertEquals("https://api.groq.com/openai/v1/chat/completions",
                    cfg.chatCompletionsUrl());
        }

        @Test
        @DisplayName("trailing slashes stripped before path appended")
        void trailingSlashHandled() {
            AiProviderConfig cfg = new AiProviderConfig(
                    "test", "Test", "key", "model",
                    "https://api.example.com///",
                    60, 8192, 0.3);
            assertEquals("https://api.example.com/chat/completions",
                    cfg.chatCompletionsUrl());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // toString()
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("returns displayName")
        void returnsDisplayName() {
            assertEquals("Groq (Free)", validConfig().toString());
        }
    }
}
