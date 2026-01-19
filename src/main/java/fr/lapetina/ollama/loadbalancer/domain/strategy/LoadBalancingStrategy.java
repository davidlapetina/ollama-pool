package fr.lapetina.ollama.loadbalancer.domain.strategy;

import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;

import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for load balancing across Ollama nodes.
 *
 * Implementations must be thread-safe as they will be called from
 * multiple Disruptor consumer threads concurrently.
 */
public interface LoadBalancingStrategy {

    /**
     * Returns the name of this strategy for configuration and metrics.
     */
    String getName();

    /**
     * Selects a node from the available nodes for the given model.
     *
     * @param nodes List of all configured nodes
     * @param model The model name requested
     * @return Selected node, or empty if no suitable node is available
     */
    Optional<OllamaNode> selectNode(List<OllamaNode> nodes, String model);

    /**
     * Called when a node successfully completes a request.
     * Strategies can use this for feedback-based balancing.
     *
     * @param node The node that completed the request
     * @param latencyMs The request latency in milliseconds
     */
    default void recordSuccess(OllamaNode node, long latencyMs) {
        // Default no-op, override for strategies that need feedback
    }

    /**
     * Called when a node fails to complete a request.
     *
     * @param node The node that failed
     */
    default void recordFailure(OllamaNode node) {
        // Default no-op, override for strategies that need feedback
    }

    /**
     * Resets any internal state. Called when nodes are reloaded.
     */
    default void reset() {
        // Default no-op
    }
}
