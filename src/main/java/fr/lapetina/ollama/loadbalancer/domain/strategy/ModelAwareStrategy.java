package fr.lapetina.ollama.loadbalancer.domain.strategy;

import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Model-aware load balancing strategy.
 *
 * Maintains separate round-robin counters per model, ensuring
 * requests for the same model are distributed evenly across
 * nodes that host that model.
 *
 * Also incorporates node preference based on recent performance
 * for each model (optional adaptive behavior).
 */
public final class ModelAwareStrategy implements LoadBalancingStrategy {

    private final Map<String, AtomicInteger> modelCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> modelNodeLatencies = new ConcurrentHashMap<>();

    private final boolean adaptiveEnabled;

    public ModelAwareStrategy() {
        this(false);
    }

    public ModelAwareStrategy(boolean adaptiveEnabled) {
        this.adaptiveEnabled = adaptiveEnabled;
    }

    @Override
    public String getName() {
        return adaptiveEnabled ? "model-aware-adaptive" : "model-aware";
    }

    @Override
    public Optional<OllamaNode> selectNode(List<OllamaNode> nodes, String model) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        // Filter to nodes that can handle this model
        List<OllamaNode> capable = nodes.stream()
                .filter(node -> node.canHandle(model))
                .toList();

        if (capable.isEmpty()) {
            return Optional.empty();
        }

        if (adaptiveEnabled) {
            return selectAdaptive(capable, model);
        }

        return selectRoundRobin(capable, model);
    }

    private Optional<OllamaNode> selectRoundRobin(List<OllamaNode> nodes, String model) {
        AtomicInteger counter = modelCounters.computeIfAbsent(
                model, k -> new AtomicInteger(0)
        );

        int index = counter.getAndIncrement() % nodes.size();
        return Optional.of(nodes.get(index));
    }

    private Optional<OllamaNode> selectAdaptive(List<OllamaNode> nodes, String model) {
        Map<String, Long> latencies = modelNodeLatencies.get(model);

        if (latencies == null || latencies.isEmpty()) {
            // No latency data yet, fall back to round-robin
            return selectRoundRobin(nodes, model);
        }

        // Select node with lowest average latency for this model
        // that still has capacity
        OllamaNode best = null;
        long bestLatency = Long.MAX_VALUE;

        for (OllamaNode node : nodes) {
            Long avgLatency = latencies.get(node.getId());
            if (avgLatency != null && avgLatency < bestLatency) {
                best = node;
                bestLatency = avgLatency;
            }
        }

        // If no latency data for available nodes, fall back to round-robin
        if (best == null) {
            return selectRoundRobin(nodes, model);
        }

        return Optional.of(best);
    }

    @Override
    public void recordSuccess(OllamaNode node, long latencyMs) {
        if (!adaptiveEnabled) {
            return;
        }

        // Update latency tracking for each model the node supports
        for (String model : node.getAvailableModels()) {
            modelNodeLatencies
                    .computeIfAbsent(model, k -> new ConcurrentHashMap<>())
                    .compute(node.getId(), (k, existing) -> {
                        if (existing == null) {
                            return latencyMs;
                        }
                        // Exponential moving average with alpha=0.3
                        return (long) (0.3 * latencyMs + 0.7 * existing);
                    });
        }
    }

    @Override
    public void reset() {
        modelCounters.clear();
        modelNodeLatencies.clear();
    }
}
