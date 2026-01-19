package fr.lapetina.ollama.loadbalancer.domain.model;

import java.net.URI;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an Ollama server node in the pool.
 * Thread-safe for concurrent access from multiple handlers.
 */
public final class OllamaNode {
    private final String id;
    private final URI baseUrl;
    private final Set<String> availableModels;
    private final int maxConcurrentRequests;
    private final int weight;
    private final boolean enabled;

    // Mutable state - thread-safe
    private final AtomicReference<NodeHealth> health;
    private final AtomicInteger inFlightRequests;
    private final AtomicInteger consecutiveFailures;
    private volatile long lastHealthCheck;
    private volatile long lastSuccessfulRequest;

    private OllamaNode(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Node ID is required");
        this.baseUrl = Objects.requireNonNull(builder.baseUrl, "Base URL is required");
        Set<String> modelsCopy = ConcurrentHashMap.newKeySet();
        modelsCopy.addAll(builder.availableModels);
        this.availableModels = Collections.unmodifiableSet(modelsCopy);
        this.maxConcurrentRequests = builder.maxConcurrentRequests;
        this.weight = builder.weight;
        this.enabled = builder.enabled;
        this.health = new AtomicReference<>(builder.initialHealth);
        this.inFlightRequests = new AtomicInteger(0);
        this.consecutiveFailures = new AtomicInteger(0);
        this.lastHealthCheck = System.currentTimeMillis();
        this.lastSuccessfulRequest = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public Set<String> getAvailableModels() {
        return availableModels;
    }

    public boolean hasModel(String model) {
        return availableModels.contains(model);
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public int getWeight() {
        return weight;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public NodeHealth getHealth() {
        return health.get();
    }

    public void setHealth(NodeHealth newHealth) {
        this.health.set(newHealth);
        this.lastHealthCheck = System.currentTimeMillis();
    }

    public int getInFlightRequests() {
        return inFlightRequests.get();
    }

    /**
     * Attempts to acquire a request slot.
     * @return true if slot acquired, false if at capacity
     */
    public boolean tryAcquireSlot() {
        while (true) {
            int current = inFlightRequests.get();
            if (current >= maxConcurrentRequests) {
                return false;
            }
            if (inFlightRequests.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Releases a request slot after completion.
     */
    public void releaseSlot() {
        inFlightRequests.decrementAndGet();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        lastSuccessfulRequest = System.currentTimeMillis();
    }

    public int recordFailure() {
        return consecutiveFailures.incrementAndGet();
    }

    public long getLastHealthCheck() {
        return lastHealthCheck;
    }

    public long getLastSuccessfulRequest() {
        return lastSuccessfulRequest;
    }

    /**
     * Checks if node is available for routing.
     * A node is available if: enabled, not DOWN, and has capacity.
     */
    public boolean isAvailable() {
        return enabled && health.get() != NodeHealth.DOWN
            && inFlightRequests.get() < maxConcurrentRequests;
    }

    /**
     * Checks if node can handle a specific model.
     */
    public boolean canHandle(String model) {
        return isAvailable() && hasModel(model);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OllamaNode that = (OllamaNode) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "OllamaNode{" +
                "id='" + id + '\'' +
                ", baseUrl=" + baseUrl +
                ", health=" + health.get() +
                ", inFlight=" + inFlightRequests.get() +
                "/" + maxConcurrentRequests +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private URI baseUrl;
        private final Set<String> availableModels = ConcurrentHashMap.newKeySet();
        private int maxConcurrentRequests = 10;
        private int weight = 1;
        private boolean enabled = true;
        private NodeHealth initialHealth = NodeHealth.UP;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder baseUrl(String url) {
            this.baseUrl = URI.create(url);
            return this;
        }

        public Builder baseUrl(URI url) {
            this.baseUrl = url;
            return this;
        }

        public Builder addModel(String model) {
            this.availableModels.add(model);
            return this;
        }

        public Builder models(Set<String> models) {
            this.availableModels.addAll(models);
            return this;
        }

        public Builder maxConcurrentRequests(int max) {
            this.maxConcurrentRequests = max;
            return this;
        }

        public Builder weight(int weight) {
            this.weight = weight;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder initialHealth(NodeHealth health) {
            this.initialHealth = health;
            return this;
        }

        public OllamaNode build() {
            return new OllamaNode(this);
        }
    }
}
