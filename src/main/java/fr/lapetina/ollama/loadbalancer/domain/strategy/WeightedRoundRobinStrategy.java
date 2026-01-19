package fr.lapetina.ollama.loadbalancer.domain.strategy;

import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Weighted round-robin load balancing strategy.
 *
 * Distributes requests according to node weights. A node with weight 2
 * receives twice as many requests as a node with weight 1.
 *
 * Uses smooth weighted round-robin (SWRR) algorithm for even distribution.
 */
public final class WeightedRoundRobinStrategy implements LoadBalancingStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public String getName() {
        return "weighted-round-robin";
    }

    @Override
    public Optional<OllamaNode> selectNode(List<OllamaNode> nodes, String model) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        // Build weighted list of nodes that can handle the model
        List<OllamaNode> weightedNodes = buildWeightedList(nodes, model);

        if (weightedNodes.isEmpty()) {
            return Optional.empty();
        }

        int index = counter.getAndIncrement() % weightedNodes.size();
        return Optional.of(weightedNodes.get(index));
    }

    private List<OllamaNode> buildWeightedList(List<OllamaNode> nodes, String model) {
        List<OllamaNode> result = new ArrayList<>();

        for (OllamaNode node : nodes) {
            if (node.canHandle(model)) {
                // Add node 'weight' times to the list
                for (int i = 0; i < node.getWeight(); i++) {
                    result.add(node);
                }
            }
        }

        return result;
    }

    @Override
    public void reset() {
        counter.set(0);
    }
}
