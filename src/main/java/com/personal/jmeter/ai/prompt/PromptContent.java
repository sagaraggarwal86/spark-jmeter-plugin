package com.personal.jmeter.ai.prompt;

import java.util.Objects;

/**
 * Immutable two-part AI request payload required by the OpenAI-compatible chat-completions API.
 *
 * <p>The system message contains the static analytical framework and output-structure
 * instructions; the user message contains the runtime test-data with all scenario
 * context substituted in.</p>
 *
 * <p>Introduced to implement the Standard 21 PromptBuilder system-prompt contract,
 * which requires separate {@code role:"system"} and {@code role:"user"} messages.</p>
 *
 * @param systemPrompt static analytical framework instructions; must not be null
 * @param userMessage  runtime test data with substituted placeholders; must not be null
 * @since 4.6.0
 */
public record PromptContent(String systemPrompt, String userMessage) {

    /**
     * Compact canonical constructor — null-checks both fields.
     */
    public PromptContent {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
    }
}