package fr.lapetina.ollama.loadbalancer.domain.event;

/**
 * Lifecycle state of an inference request event in the Disruptor pipeline.
 */
public enum EventState {
    /** Event just created, awaiting validation */
    CREATED,

    /** Request validated successfully */
    VALIDATED,

    /** Validation failed */
    VALIDATION_FAILED,

    /** Node has been selected for this request */
    NODE_SELECTED,

    /** No node available (all at capacity or none have model) */
    NO_NODE_AVAILABLE,

    /** Rate limited - request will be retried or rejected */
    RATE_LIMITED,

    /** Request dispatched to target node */
    DISPATCHED,

    /** Response received from node */
    RESPONSE_RECEIVED,

    /** Request completed successfully */
    COMPLETED,

    /** Request failed after all retries */
    FAILED,

    /** Request timed out */
    TIMED_OUT,

    /** Request cancelled by client */
    CANCELLED
}
