package fr.lapetina.ollama.loadbalancer.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaNodeTest {

    private OllamaNode node;

    @BeforeEach
    void setUp() {
        node = OllamaNode.builder()
                .id("test-node")
                .baseUrl("http://localhost:11434")
                .addModel("llama2")
                .addModel("codellama")
                .maxConcurrentRequests(3)
                .weight(2)
                .build();
    }

    @Test
    @DisplayName("should create node with builder")
    void shouldCreateNodeWithBuilder() {
        assertThat(node.getId()).isEqualTo("test-node");
        assertThat(node.getBaseUrl().toString()).isEqualTo("http://localhost:11434");
        assertThat(node.getAvailableModels()).containsExactlyInAnyOrder("llama2", "codellama");
        assertThat(node.getMaxConcurrentRequests()).isEqualTo(3);
        assertThat(node.getWeight()).isEqualTo(2);
        assertThat(node.isEnabled()).isTrue();
        assertThat(node.getHealth()).isEqualTo(NodeHealth.UP);
    }

    @Test
    @DisplayName("should check model availability")
    void shouldCheckModelAvailability() {
        assertThat(node.hasModel("llama2")).isTrue();
        assertThat(node.hasModel("codellama")).isTrue();
        assertThat(node.hasModel("mistral")).isFalse();
    }

    @Test
    @DisplayName("should track in-flight requests")
    void shouldTrackInFlightRequests() {
        assertThat(node.getInFlightRequests()).isZero();

        assertThat(node.tryAcquireSlot()).isTrue();
        assertThat(node.getInFlightRequests()).isEqualTo(1);

        assertThat(node.tryAcquireSlot()).isTrue();
        assertThat(node.getInFlightRequests()).isEqualTo(2);

        node.releaseSlot();
        assertThat(node.getInFlightRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("should enforce max concurrent requests")
    void shouldEnforceMaxConcurrentRequests() {
        // Acquire all slots
        assertThat(node.tryAcquireSlot()).isTrue();
        assertThat(node.tryAcquireSlot()).isTrue();
        assertThat(node.tryAcquireSlot()).isTrue();

        // Should fail - at capacity
        assertThat(node.tryAcquireSlot()).isFalse();

        // Release one
        node.releaseSlot();

        // Should succeed now
        assertThat(node.tryAcquireSlot()).isTrue();
    }

    @Test
    @DisplayName("should be thread-safe for concurrent slot operations")
    void shouldBeThreadSafeForSlotOperations() throws InterruptedException {
        int threads = 10;
        int iterations = 1000;
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger successfulAcquisitions = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        if (node.tryAcquireSlot()) {
                            successfulAcquisitions.incrementAndGet();
                            // Simulate some work
                            Thread.yield();
                            node.releaseSlot();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // After all threads complete, should have 0 in-flight
        assertThat(node.getInFlightRequests()).isZero();
        // Should have had some successful acquisitions
        assertThat(successfulAcquisitions.get()).isPositive();
    }

    @Test
    @DisplayName("should track consecutive failures")
    void shouldTrackConsecutiveFailures() {
        assertThat(node.getConsecutiveFailures()).isZero();

        assertThat(node.recordFailure()).isEqualTo(1);
        assertThat(node.recordFailure()).isEqualTo(2);
        assertThat(node.recordFailure()).isEqualTo(3);

        node.recordSuccess();
        assertThat(node.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("should update health status")
    void shouldUpdateHealthStatus() {
        assertThat(node.getHealth()).isEqualTo(NodeHealth.UP);

        node.setHealth(NodeHealth.DEGRADED);
        assertThat(node.getHealth()).isEqualTo(NodeHealth.DEGRADED);

        node.setHealth(NodeHealth.DOWN);
        assertThat(node.getHealth()).isEqualTo(NodeHealth.DOWN);
    }

    @Test
    @DisplayName("should determine availability correctly")
    void shouldDetermineAvailabilityCorrectly() {
        // Initially available
        assertThat(node.isAvailable()).isTrue();
        assertThat(node.canHandle("llama2")).isTrue();

        // Fill up capacity
        node.tryAcquireSlot();
        node.tryAcquireSlot();
        node.tryAcquireSlot();
        assertThat(node.isAvailable()).isFalse();
        assertThat(node.canHandle("llama2")).isFalse();

        // Release slot - available again
        node.releaseSlot();
        assertThat(node.isAvailable()).isTrue();

        // Mark DOWN - not available
        node.setHealth(NodeHealth.DOWN);
        assertThat(node.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("should have correct equality based on ID")
    void shouldHaveCorrectEquality() {
        OllamaNode sameId = OllamaNode.builder()
                .id("test-node")
                .baseUrl("http://different:11434")
                .build();

        OllamaNode differentId = OllamaNode.builder()
                .id("other-node")
                .baseUrl("http://localhost:11434")
                .build();

        assertThat(node).isEqualTo(sameId);
        assertThat(node).isNotEqualTo(differentId);
        assertThat(node.hashCode()).isEqualTo(sameId.hashCode());
    }
}
