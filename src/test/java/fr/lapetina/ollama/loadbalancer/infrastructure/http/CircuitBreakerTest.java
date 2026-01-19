package fr.lapetina.ollama.loadbalancer.infrastructure.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // Fast circuit breaker for testing: 3 failures, 100ms recovery
        circuitBreaker = new CircuitBreaker("test-node", 3, Duration.ofMillis(100), 2);
    }

    @Test
    @DisplayName("should start in CLOSED state")
    void shouldStartClosed() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("should remain CLOSED after successes")
    void shouldRemainClosedAfterSuccesses() {
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getFailureCount()).isZero();
    }

    @Test
    @DisplayName("should open after threshold failures")
    void shouldOpenAfterThresholdFailures() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        circuitBreaker.recordFailure(); // Third failure hits threshold

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("should reset failure count on success")
    void shouldResetFailureCountOnSuccess() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getFailureCount()).isEqualTo(2);

        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.getFailureCount()).isZero();
    }

    @Test
    @DisplayName("should transition to HALF_OPEN after recovery timeout")
    void shouldTransitionToHalfOpenAfterTimeout() throws InterruptedException {
        // Open the circuit
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait for recovery timeout
        Thread.sleep(150);

        // Should transition to HALF_OPEN when checking
        assertThat(circuitBreaker.allowRequest()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("should close after successful requests in HALF_OPEN")
    void shouldCloseAfterSuccessesInHalfOpen() throws InterruptedException {
        // Open and wait for recovery
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        Thread.sleep(150);
        circuitBreaker.allowRequest(); // Trigger transition to HALF_OPEN

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Record enough successes (threshold is 2)
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should reopen on failure in HALF_OPEN")
    void shouldReopenOnFailureInHalfOpen() throws InterruptedException {
        // Open and wait for recovery
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        Thread.sleep(150);
        circuitBreaker.allowRequest(); // Trigger transition to HALF_OPEN

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Any failure in HALF_OPEN should reopen
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("should allow forcing state")
    void shouldAllowForcingState() {
        circuitBreaker.forceState(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        circuitBreaker.forceState(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getFailureCount()).isZero();
    }
}
