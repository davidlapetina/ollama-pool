package fr.lapetina.ollama.loadbalancer.infrastructure.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker implementation for Ollama node protection.
 *
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Failures exceeded threshold, requests rejected immediately
 * - HALF_OPEN: After recovery timeout, allow one test request through
 *
 * Thread-safe via atomic operations.
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String nodeId;
    private final int failureThreshold;
    private final Duration recoveryTimeout;
    private final int successThresholdInHalfOpen;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCountInHalfOpen = new AtomicInteger(0);
    private volatile Instant lastFailureTime;
    private volatile Instant openedAt;

    public CircuitBreaker(
            String nodeId,
            int failureThreshold,
            Duration recoveryTimeout,
            int successThresholdInHalfOpen
    ) {
        this.nodeId = nodeId;
        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
        this.successThresholdInHalfOpen = successThresholdInHalfOpen;
    }

    public CircuitBreaker(String nodeId) {
        this(nodeId, 5, Duration.ofSeconds(30), 3);
    }

    /**
     * Checks if a request is allowed through the circuit breaker.
     *
     * @return true if request should proceed, false if circuit is open
     */
    public boolean allowRequest() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                // Check if recovery timeout has elapsed
                if (Instant.now().isAfter(openedAt.plus(recoveryTimeout))) {
                    // Transition to half-open
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        successCountInHalfOpen.set(0);
                        log.info("Circuit breaker transitioning to HALF_OPEN: nodeId={}", nodeId);
                    }
                    return true;
                }
                return false;

            case HALF_OPEN:
                // In half-open, allow limited requests
                return true;

            default:
                return true;
        }
    }

    /**
     * Records a successful request.
     */
    public void recordSuccess() {
        State currentState = state.get();

        if (currentState == State.CLOSED) {
            // Reset failure count on success
            failureCount.set(0);
            return;
        }

        if (currentState == State.HALF_OPEN) {
            int successes = successCountInHalfOpen.incrementAndGet();
            if (successes >= successThresholdInHalfOpen) {
                // Enough successes, close the circuit
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    failureCount.set(0);
                    log.info("Circuit breaker CLOSED after recovery: nodeId={}", nodeId);
                }
            }
        }
    }

    /**
     * Records a failed request.
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // Any failure in half-open immediately opens the circuit
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt = Instant.now();
                log.warn("Circuit breaker OPENED (half-open failure): nodeId={}", nodeId);
            }
            return;
        }

        if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                // Open the circuit
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt = Instant.now();
                    log.warn("Circuit breaker OPENED: nodeId={}, failures={}", nodeId, failures);
                }
            }
        }
    }

    /**
     * Forces the circuit to a specific state. For testing/admin use.
     */
    public void forceState(State newState) {
        State old = state.getAndSet(newState);
        if (newState == State.CLOSED) {
            failureCount.set(0);
        }
        if (newState == State.OPEN) {
            openedAt = Instant.now();
        }
        log.info("Circuit breaker forced from {} to {}: nodeId={}", old, newState, nodeId);
    }

    public State getState() {
        // Check for automatic transition from OPEN to HALF_OPEN
        if (state.get() == State.OPEN && openedAt != null) {
            if (Instant.now().isAfter(openedAt.plus(recoveryTimeout))) {
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                successCountInHalfOpen.set(0);
            }
        }
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public Instant getLastFailureTime() {
        return lastFailureTime;
    }

    public String getNodeId() {
        return nodeId;
    }

    @Override
    public String toString() {
        return "CircuitBreaker{" +
                "nodeId='" + nodeId + '\'' +
                ", state=" + state.get() +
                ", failures=" + failureCount.get() +
                '}';
    }
}
