package com.personal.jmeter.ai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the AI reporter system prompt from the bundled JAR resource.
 *
 * <p>The prompt is always read from the classpath resource
 * {@code /ai-reporter-prompt.txt} packaged inside the plugin JAR.
 * No external file system paths are consulted — the prompt is an
 * integral part of the plugin, not a user-editable configuration file.</p>
 *
 * <p>Returns {@code null} only if the resource is missing or empty,
 * allowing the caller to show an appropriate error dialog.</p>
 *
 * <p>All public methods are static; this class is a stateless utility.</p>
 * @since 4.6.0
 */
public final class PromptLoader {

    static final String PROMPT_FILE_NAME = "ai-reporter-prompt.txt";
    static final String RESOURCE_PATH = "/" + PROMPT_FILE_NAME;
    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private PromptLoader() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads the system prompt from the bundled JAR resource.
     *
     * @return prompt text, or {@code null} if the resource is missing or empty
     */
    public static String load() {
        String text = readResource();
        if (text != null) {
            log.debug("load: loaded prompt from bundled JAR resource.");
            return text;
        }

        log.error("load: bundled JAR resource {} not found or empty. "
                + "Plugin JAR may be corrupt.", RESOURCE_PATH);
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String readResource() {
        try (InputStream in = PromptLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.warn("readResource: resource not found: {}", RESOURCE_PATH);
                return null;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            log.error("readResource: failed to read {}. reason={}", RESOURCE_PATH, e.getMessage(), e);
            return null;
        }
    }
}