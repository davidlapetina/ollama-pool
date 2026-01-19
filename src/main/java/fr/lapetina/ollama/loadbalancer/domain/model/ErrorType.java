package fr.lapetina.ollama.loadbalancer.domain.model;

/**
 * Error taxonomy for inference requests.
 * Provides clear categorization for error handling and metrics.
 */
public enum ErrorType {
    /** Client-side error (invalid request, bad model name, etc.) */
    CLIENT_ERROR,

    /** Node-side error (Ollama server error, model load failure, etc.) */
    NODE_ERROR,

    /** Capacity error (all nodes at capacity, backpressure triggered) */
    CAPACITY_ERROR,

    /** Request timed out waiting for response */
    TIMEOUT,

    /** Circuit breaker is open for the target node */
    CIRCUIT_OPEN,

    /** No healthy nodes available for the requested model */
    NO_AVAILABLE_NODE,

    /** Validation error in request */
    VALIDATION_ERROR,

    /** Internal system error */
    INTERNAL_ERROR
}
