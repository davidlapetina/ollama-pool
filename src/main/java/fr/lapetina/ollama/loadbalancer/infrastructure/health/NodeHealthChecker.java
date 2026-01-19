package fr.lapetina.ollama.loadbalancer.infrastructure.health;

import fr.lapetina.ollama.loadbalancer.domain.model.NodeHealth;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import fr.lapetina.ollama.loadbalancer.infrastructure.http.CircuitBreaker;
import fr.lapetina.ollama.loadbalancer.infrastructure.http.OllamaHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background health checker for Ollama nodes.
 *
 * Periodically polls each node and updates health status.
 * Considers both direct health check results and circuit breaker state.
 */
public final class NodeHealthChecker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthChecker.class);

    private final NodeRegistry nodeRegistry;
    private final OllamaHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Duration checkInterval;
    private final int degradedThreshold;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NodeHealthChecker(
            NodeRegistry nodeRegistry,
            OllamaHttpClient httpClient,
            Duration checkInterval,
            int degradedThreshold
    ) {
        this.nodeRegistry = nodeRegistry;
        this.httpClient = httpClient;
        this.checkInterval = checkInterval;
        this.degradedThreshold = degradedThreshold;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true);
            return t;
        });
    }

    public NodeHealthChecker(NodeRegistry nodeRegistry, OllamaHttpClient httpClient) {
        this(nodeRegistry, httpClient, Duration.ofSeconds(30), 3);
    }

    /**
     * Starts the periodic health checking.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(
                    this::checkAllNodes,
                    0,
                    checkInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            log.info("Health checker started with interval: {}", checkInterval);
        }
    }

    /**
     * Performs immediate health check on all nodes.
     */
    public void checkAllNodes() {
        if (!running.get()) {
            return;
        }

        var enabledNodes = nodeRegistry.getEnabledNodes();
        log.debug("Starting health check cycle: nodeCount={}", enabledNodes.size());

        for (OllamaNode node : enabledNodes) {
            checkNode(node);
        }
    }

    /**
     * Performs health check on a single node.
     */
    public void checkNode(OllamaNode node) {
        log.debug("Health check started: nodeId={}, currentHealth={}, consecutiveFailures={}",
                node.getId(), node.getHealth(), node.getConsecutiveFailures());

        try {
            httpClient.healthCheck(node)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .whenComplete((healthy, ex) -> {
                        if (ex != null) {
                            handleHealthCheckFailure(node, ex);
                        } else {
                            handleHealthCheckResult(node, healthy);
                        }
                    });
        } catch (Exception e) {
            handleHealthCheckFailure(node, e);
        }
    }

    private void handleHealthCheckResult(OllamaNode node, boolean healthy) {
        if (healthy) {
            // Check circuit breaker state as well
            CircuitBreaker breaker = httpClient.getCircuitBreaker(node.getId());
            if (breaker != null && breaker.getState() == CircuitBreaker.State.OPEN) {
                // Node responds but circuit is open - degraded
                log.info("Health check passed but circuit breaker open: nodeId={}, setting DEGRADED",
                        node.getId());
                setNodeHealth(node, NodeHealth.DEGRADED);
            } else if (node.getConsecutiveFailures() >= degradedThreshold) {
                // Recent failures but responding - degraded
                log.info("Health check passed but has recent failures: nodeId={}, failures={}, setting DEGRADED",
                        node.getId(), node.getConsecutiveFailures());
                setNodeHealth(node, NodeHealth.DEGRADED);
            } else {
                log.debug("Health check passed: nodeId={}, setting UP", node.getId());
                setNodeHealth(node, NodeHealth.UP);
            }
        } else {
            handleHealthCheckFailure(node, null);
        }
    }

    private void handleHealthCheckFailure(OllamaNode node, Throwable ex) {
        int failures = node.recordFailure();

        NodeHealth newHealth;
        if (failures >= degradedThreshold * 2) {
            newHealth = NodeHealth.DOWN;
        } else if (failures >= degradedThreshold) {
            newHealth = NodeHealth.DEGRADED;
        } else {
            newHealth = node.getHealth(); // Keep current health
        }

        if (ex != null) {
            log.warn("Health check failed: nodeId={}, consecutiveFailures={}, newHealth={}, error={}",
                    node.getId(), failures, newHealth, ex.getMessage());
        } else {
            log.warn("Health check returned unhealthy: nodeId={}, consecutiveFailures={}, newHealth={}",
                    node.getId(), failures, newHealth);
        }

        if (failures >= degradedThreshold) {
            setNodeHealth(node, newHealth);
        }
    }

    private void setNodeHealth(OllamaNode node, NodeHealth health) {
        NodeHealth previous = node.getHealth();
        if (previous != health) {
            log.info("Node health changed: nodeId={}, previousHealth={}, newHealth={}, consecutiveFailures={}",
                    node.getId(), previous, health, node.getConsecutiveFailures());
            nodeRegistry.updateNodeHealth(node.getId(), health);
        }
    }

    /**
     * Forces a health check and waits for completion.
     */
    public void forceCheck(String nodeId) {
        nodeRegistry.getNode(nodeId).ifPresent(this::checkNode);
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Health checker stopped");
        }
    }
}
