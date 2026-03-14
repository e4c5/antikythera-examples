package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;

/**
 * Thrown when an AI service returns a response that cannot be parsed
 * into the expected format.
 */
public class AIResponseException extends AntikytheraException {

    public AIResponseException(String message) {
        super(message);
    }

    public AIResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
