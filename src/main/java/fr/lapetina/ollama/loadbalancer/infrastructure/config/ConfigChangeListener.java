package fr.lapetina.ollama.loadbalancer.infrastructure.config;

/**
 * Listener interface for configuration changes.
 */
@FunctionalInterface
public interface ConfigChangeListener {

    /**
     * Called when configuration has been reloaded.
     *
     * @param oldConfig The previous configuration (may be null on initial load)
     * @param newConfig The new configuration
     */
    void onConfigChanged(LoadBalancerConfig oldConfig, LoadBalancerConfig newConfig);
}
