package fr.lapetina.ollama.loadbalancer.disruptor.exception;

/**
 * Exception thrown when the system is under backpressure.
 *
 * This occurs when:
 * - Ring buffer is full and cannot accept new requests
 * - Global in-flight limit is reached
 * - All nodes are at capacity
 */
public final class BackpressureException extends RuntimeException {

    private final BackpressureReason reason;

    public BackpressureException(BackpressureReason reason) {
        super("Backpressure: " + reason.getMessage());
        this.reason = reason;
    }

    public BackpressureException(BackpressureReason reason, String details) {
        super("Backpressure: " + reason.getMessage() + " - " + details);
        this.reason = reason;
    }

    public BackpressureReason getReason() {
        return reason;
    }

    public enum BackpressureReason {
        RING_BUFFER_FULL("Ring buffer is full"),
        GLOBAL_LIMIT_REACHED("Global in-flight request limit reached"),
        ALL_NODES_AT_CAPACITY("All nodes are at maximum capacity"),
        NO_HEALTHY_NODES("No healthy nodes available");

        private final String message;

        BackpressureReason(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }
}
