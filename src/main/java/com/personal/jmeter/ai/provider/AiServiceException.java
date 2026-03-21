package com.personal.jmeter.ai.provider;

import java.io.IOException;

/**
 * Domain exception thrown when the AI API returns an error, an empty response,
 * or when all retry attempts are exhausted.
 *
 * <p>Extends {@link IOException} so callers that declare {@code throws IOException}
 * need no signature change.</p>
 * @since 4.6.0
 */
public class AiServiceException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a descriptive message.
     *
     * @param message the detail message
     */
    public AiServiceException(String message) {
        super(message);
    }

    /**
     * Constructs the exception with a descriptive message and a root cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}