package fr.lapetina.ollama.loadbalancer.domain.strategy;

import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;

import java.util.List;
import java.util.Optional;

/**
 * Least-in-flight (least connections) load balancing strategy.
 *
 * Selects the node with the fewest active requests. This naturally
 * adapts to varying response times and node capacities.
 *
 * Thread-safe as it reads atomic counters from OllamaNode.
 */
public final class LeastInFlightStrategy implements LoadBalancingStrategy {

    @Override
    public String getName() {
        return "least-in-flight";
    }

    @Override
    public Optional<OllamaNode> selectNode(List<OllamaNode> nodes, String model) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        OllamaNode selected = null;
        int minInFlight = Integer.MAX_VALUE;
        double minRatio = Double.MAX_VALUE;

        for (OllamaNode node : nodes) {
            if (!node.canHandle(model)) {
                continue;
            }

            int inFlight = node.getInFlightRequests();
            int maxConcurrent = node.getMaxConcurrentRequests();

            // Calculate utilization ratio for fair comparison across different capacities
            double ratio = (double) inFlight / maxConcurrent;

            // Prefer lower ratio, break ties with absolute in-flight count
            if (ratio < minRatio || (ratio == minRatio && inFlight < minInFlight)) {
                minRatio = ratio;
                minInFlight = inFlight;
                selected = node;
            }
        }

        return Optional.ofNullable(selected);
    }
}
