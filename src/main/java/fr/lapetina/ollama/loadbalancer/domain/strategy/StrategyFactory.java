package fr.lapetina.ollama.loadbalancer.domain.strategy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Factory for creating and caching load balancing strategies.
 *
 * Supports runtime strategy switching without service restart.
 */
public final class StrategyFactory {

    private static final Map<String, Supplier<LoadBalancingStrategy>> REGISTRY = new ConcurrentHashMap<>();

    static {
        // Register built-in strategies
        register("round-robin", RoundRobinStrategy::new);
        register("weighted-round-robin", WeightedRoundRobinStrategy::new);
        register("least-in-flight", LeastInFlightStrategy::new);
        register("random", RandomStrategy::new);
        register("model-aware", ModelAwareStrategy::new);
        register("model-aware-adaptive", () -> new ModelAwareStrategy(true));
    }

    private StrategyFactory() {
        // Utility class
    }

    /**
     * Registers a custom strategy.
     *
     * @param name Strategy name (used in configuration)
     * @param supplier Factory for creating strategy instances
     */
    public static void register(String name, Supplier<LoadBalancingStrategy> supplier) {
        REGISTRY.put(name.toLowerCase(), supplier);
    }

    /**
     * Creates a strategy by name.
     *
     * @param name Strategy name from configuration
     * @return Strategy instance, or empty if not found
     */
    public static Optional<LoadBalancingStrategy> create(String name) {
        Supplier<LoadBalancingStrategy> supplier = REGISTRY.get(name.toLowerCase());
        if (supplier == null) {
            return Optional.empty();
        }
        return Optional.of(supplier.get());
    }

    /**
     * Creates a strategy by name, with default fallback.
     *
     * @param name Strategy name from configuration
     * @param defaultStrategy Default if name not found
     * @return Strategy instance
     */
    public static LoadBalancingStrategy createOrDefault(
            String name,
            LoadBalancingStrategy defaultStrategy
    ) {
        return create(name).orElse(defaultStrategy);
    }

    /**
     * Returns all registered strategy names.
     */
    public static Iterable<String> getRegisteredNames() {
        return REGISTRY.keySet();
    }
}
