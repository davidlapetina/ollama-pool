package fr.lapetina.ollama.loadbalancer.domain.strategy;

import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Random load balancing strategy.
 *
 * Randomly selects from available nodes. Simple but effective for
 * homogeneous node pools with similar capacities.
 *
 * Thread-safe via ThreadLocalRandom.
 */
public final class RandomStrategy implements LoadBalancingStrategy {

    @Override
    public String getName() {
        return "random";
    }

    @Override
    public Optional<OllamaNode> selectNode(List<OllamaNode> nodes, String model) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        List<OllamaNode> available = nodes.stream()
                .filter(node -> node.canHandle(model))
                .collect(Collectors.toList());

        if (available.isEmpty()) {
            return Optional.empty();
        }

        int index = ThreadLocalRandom.current().nextInt(available.size());
        return Optional.of(available.get(index));
    }
}
