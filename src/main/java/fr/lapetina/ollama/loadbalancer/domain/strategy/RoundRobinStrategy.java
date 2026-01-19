package fr.lapetina.ollama.loadbalancer.domain.strategy;

import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple round-robin load balancing strategy.
 *
 * Cycles through available nodes in order, skipping nodes that
 * cannot handle the requested model or are unavailable.
 *
 * Thread-safe via atomic counter.
 */
public final class RoundRobinStrategy implements LoadBalancingStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public String getName() {
        return "round-robin";
    }

    @Override
    public Optional<OllamaNode> selectNode(List<OllamaNode> nodes, String model) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        int size = nodes.size();
        int startIndex = counter.getAndIncrement() % size;

        // Try each node starting from current counter position
        for (int i = 0; i < size; i++) {
            int index = (startIndex + i) % size;
            OllamaNode node = nodes.get(index);

            if (node.canHandle(model)) {
                return Optional.of(node);
            }
        }

        return Optional.empty();
    }

    @Override
    public void reset() {
        counter.set(0);
    }
}
