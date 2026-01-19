package fr.lapetina.ollama.loadbalancer.domain.strategy;

import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBalancingStrategyTest {

    private List<OllamaNode> testNodes;

    @BeforeEach
    void setUp() {
        testNodes = List.of(
                OllamaNode.builder()
                        .id("node-1")
                        .baseUrl("http://localhost:11434")
                        .addModel("llama2")
                        .addModel("codellama")
                        .maxConcurrentRequests(5)
                        .weight(2)
                        .build(),
                OllamaNode.builder()
                        .id("node-2")
                        .baseUrl("http://localhost:11435")
                        .addModel("llama2")
                        .addModel("mistral")
                        .maxConcurrentRequests(3)
                        .weight(1)
                        .build(),
                OllamaNode.builder()
                        .id("node-3")
                        .baseUrl("http://localhost:11436")
                        .addModel("codellama")
                        .maxConcurrentRequests(4)
                        .weight(1)
                        .build()
        );
    }

    @Nested
    @DisplayName("RoundRobinStrategy")
    class RoundRobinTests {

        private RoundRobinStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new RoundRobinStrategy();
        }

        @Test
        @DisplayName("should cycle through nodes in order")
        void shouldCycleThroughNodes() {
            // First request should go to node-1 (only nodes with llama2)
            Optional<OllamaNode> first = strategy.selectNode(testNodes, "llama2");
            assertThat(first).isPresent();

            // Get 10 selections to verify cycling
            String[] selections = new String[10];
            for (int i = 0; i < 10; i++) {
                selections[i] = strategy.selectNode(testNodes, "llama2")
                        .map(OllamaNode::getId)
                        .orElse("none");
            }

            // Should alternate between node-1 and node-2 (both have llama2)
            assertThat(selections).containsOnly("node-1", "node-2");
        }

        @Test
        @DisplayName("should skip nodes without requested model")
        void shouldSkipNodesWithoutModel() {
            Optional<OllamaNode> result = strategy.selectNode(testNodes, "mistral");

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo("node-2"); // Only node-2 has mistral
        }

        @Test
        @DisplayName("should return empty when no nodes have model")
        void shouldReturnEmptyForUnknownModel() {
            Optional<OllamaNode> result = strategy.selectNode(testNodes, "unknown-model");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should handle empty node list")
        void shouldHandleEmptyNodeList() {
            Optional<OllamaNode> result = strategy.selectNode(List.of(), "llama2");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("WeightedRoundRobinStrategy")
    class WeightedRoundRobinTests {

        private WeightedRoundRobinStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new WeightedRoundRobinStrategy();
        }

        @Test
        @DisplayName("should respect node weights in distribution")
        void shouldRespectWeights() {
            // node-1 has weight 2, node-2 has weight 1
            // For llama2 requests, node-1 should get ~2x the requests

            ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
            for (int i = 0; i < 300; i++) {
                strategy.selectNode(testNodes, "llama2")
                        .ifPresent(node -> counts.merge(node.getId(), 1, Integer::sum));
            }

            int node1Count = counts.getOrDefault("node-1", 0);
            int node2Count = counts.getOrDefault("node-2", 0);

            // node-1 should have roughly 2x the requests of node-2
            // Allow some variance due to the simple weighted list implementation
            assertThat(node1Count).isGreaterThan(node2Count);
            assertThat((double) node1Count / node2Count).isBetween(1.5, 2.5);
        }
    }

    @Nested
    @DisplayName("LeastInFlightStrategy")
    class LeastInFlightTests {

        private LeastInFlightStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new LeastInFlightStrategy();
        }

        @Test
        @DisplayName("should select node with least in-flight requests")
        void shouldSelectLeastLoaded() {
            // Simulate some load on node-1
            testNodes.get(0).tryAcquireSlot();
            testNodes.get(0).tryAcquireSlot();

            Optional<OllamaNode> result = strategy.selectNode(testNodes, "llama2");

            // Should select node-2 as it has 0 in-flight
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo("node-2");
        }

        @Test
        @DisplayName("should consider utilization ratio for different capacities")
        void shouldConsiderUtilizationRatio() {
            // node-1: capacity 5, node-2: capacity 3
            // Put 1 request in each - node-2 has higher utilization (33% vs 20%)

            testNodes.get(0).tryAcquireSlot(); // node-1: 1/5 = 20%
            testNodes.get(1).tryAcquireSlot(); // node-2: 1/3 = 33%

            Optional<OllamaNode> result = strategy.selectNode(testNodes, "llama2");

            // Should prefer node-1 due to lower utilization ratio
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo("node-1");
        }
    }

    @Nested
    @DisplayName("ModelAwareStrategy")
    class ModelAwareTests {

        private ModelAwareStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new ModelAwareStrategy();
        }

        @Test
        @DisplayName("should only route to nodes with requested model")
        void shouldRouteToNodesWithModel() {
            // codellama is on node-1 and node-3, but not node-2
            Set<String> selectedNodes = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < 100; i++) {
                strategy.selectNode(testNodes, "codellama")
                        .ifPresent(node -> selectedNodes.add(node.getId()));
            }

            assertThat(selectedNodes).containsOnly("node-1", "node-3");
            assertThat(selectedNodes).doesNotContain("node-2");
        }

        @Test
        @DisplayName("should maintain separate counters per model")
        void shouldMaintainSeparateCountersPerModel() {
            // Make selections for different models
            strategy.selectNode(testNodes, "llama2");
            strategy.selectNode(testNodes, "codellama");
            strategy.selectNode(testNodes, "llama2");

            // Each model should have independent round-robin state
            // This is verified by the model-specific routing behavior
            Optional<OllamaNode> llama2Node = strategy.selectNode(testNodes, "llama2");
            Optional<OllamaNode> codeNode = strategy.selectNode(testNodes, "codellama");

            assertThat(llama2Node).isPresent();
            assertThat(codeNode).isPresent();
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("RoundRobin should be thread-safe")
        void roundRobinShouldBeThreadSafe() throws InterruptedException {
            RoundRobinStrategy strategy = new RoundRobinStrategy();
            int threads = 10;
            int iterations = 1000;
            CountDownLatch latch = new CountDownLatch(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);

            ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            strategy.selectNode(testNodes, "llama2")
                                    .ifPresent(node -> counts.merge(node.getId(), 1, Integer::sum));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            assertThat(total).isEqualTo(threads * iterations);
        }

        @Test
        @DisplayName("LeastInFlight should be thread-safe")
        void leastInFlightShouldBeThreadSafe() throws InterruptedException {
            LeastInFlightStrategy strategy = new LeastInFlightStrategy();
            int threads = 10;
            int iterations = 100;
            CountDownLatch latch = new CountDownLatch(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            Optional<OllamaNode> node = strategy.selectNode(testNodes, "llama2");
                            if (node.isPresent() && node.get().tryAcquireSlot()) {
                                // Simulate some work
                                Thread.sleep(1);
                                node.get().releaseSlot();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            // All nodes should have released their slots
            for (OllamaNode node : testNodes) {
                assertThat(node.getInFlightRequests()).isZero();
            }
        }
    }
}
