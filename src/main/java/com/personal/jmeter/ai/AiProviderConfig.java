package com.personal.jmeter.ai;

/**
 * Immutable value object holding the fully-resolved configuration for one AI provider.
 *
 * <p>Instances are created by {@link AiProviderRegistry} after reading and merging
 * {@code ai-reporter.properties} with built-in provider defaults. All fields are
 * guaranteed non-null and non-blank when produced by the registry.</p>
 */
public final class AiProviderConfig {

    /** Internal provider key as it appears in the properties file (e.g. {@code groq}). */
    public final String providerKey;

    /** Human-readable label shown in the UI dropdown (e.g. {@code Groq (Free)}). */
    public final String displayName;

    /** API key. Non-blank — providers with blank keys are not surfaced by the registry. */
    public final String apiKey;

    /** Model identifier (e.g. {@code llama-3.3-70b-versatile}). */
    public final String model;

    /**
     * Base URL for the OpenAI-compatible endpoint, without trailing slash
     * (e.g. {@code https://api.groq.com/openai/v1}).
     */
    public final String baseUrl;

    /** Request + connect timeout in seconds. */
    public final int timeoutSeconds;

    /** Maximum tokens the model may return. */
    public final int maxTokens;

    /** Sampling temperature (0.0–2.0). */
    public final double temperature;

    /**
     * Constructs a fully-resolved provider configuration.
     *
     * @param providerKey    internal key (must not be blank)
     * @param displayName    UI label (must not be blank)
     * @param apiKey         API credential (must not be blank)
     * @param model          model identifier (must not be blank)
     * @param baseUrl        base endpoint URL, no trailing slash (must not be blank)
     * @param timeoutSeconds request timeout in seconds (must be &gt; 0)
     * @param maxTokens      maximum response tokens (must be &gt; 0)
     * @param temperature    sampling temperature
     */
    public AiProviderConfig(String providerKey, String displayName, String apiKey,
                            String model, String baseUrl,
                            int timeoutSeconds, int maxTokens, double temperature) {
        if (providerKey == null || providerKey.isBlank())
            throw new IllegalArgumentException("providerKey must not be blank");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("displayName must not be blank");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("apiKey must not be blank");
        if (model == null || model.isBlank())
            throw new IllegalArgumentException("model must not be blank");
        if (baseUrl == null || baseUrl.isBlank())
            throw new IllegalArgumentException("baseUrl must not be blank");
        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be > 0");
        if (maxTokens <= 0)
            throw new IllegalArgumentException("maxTokens must be > 0");

        this.providerKey    = providerKey;
        this.displayName    = displayName;
        this.apiKey         = apiKey;
        this.model          = model.trim();
        this.baseUrl        = baseUrl.trim().replaceAll("/+$", ""); // strip trailing slash
        this.timeoutSeconds = timeoutSeconds;
        this.maxTokens      = maxTokens;
        this.temperature    = temperature;
    }

    /**
     * Returns the full chat-completions endpoint URL derived from {@link #baseUrl}.
     *
     * @return {@code baseUrl + "/chat/completions"}
     */
    public String chatCompletionsUrl() {
        return baseUrl + "/chat/completions";
    }

    /** Returns the {@link #displayName} so the JComboBox renderer needs no customisation. */
    @Override
    public String toString() {
        return displayName;
    }
}
