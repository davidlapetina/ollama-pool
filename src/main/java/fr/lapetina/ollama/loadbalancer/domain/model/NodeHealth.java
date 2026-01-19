package fr.lapetina.ollama.loadbalancer.domain.model;

/**
 * Health status of an Ollama node.
 *
 * UP: Node is healthy and accepting requests
 * DEGRADED: Node is responding but with issues (high latency, partial failures)
 * DOWN: Node is not responding or explicitly marked offline
 */
public enum NodeHealth {
    UP,
    DEGRADED,
    DOWN
}
