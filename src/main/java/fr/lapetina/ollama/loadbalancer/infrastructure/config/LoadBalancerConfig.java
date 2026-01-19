package fr.lapetina.ollama.loadbalancer.infrastructure.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Root configuration object for the load balancer.
 * Designed to be populated from YAML.
 */
public class LoadBalancerConfig {

    private ServerConfig server = new ServerConfig();
    private List<NodeConfig> nodes = new ArrayList<>();
    private StrategyConfig strategy = new StrategyConfig();
    private DisruptorConfig disruptor = new DisruptorConfig();
    private TimeoutsConfig timeouts = new TimeoutsConfig();
    private RetryConfig retry = new RetryConfig();
    private HealthCheckConfig healthCheck = new HealthCheckConfig();
    private ValidationConfig validation = new ValidationConfig();
    private MetricsConfig metrics = new MetricsConfig();

    // Getters and Setters
    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }

    public List<NodeConfig> getNodes() { return nodes; }
    public void setNodes(List<NodeConfig> nodes) { this.nodes = nodes; }

    public StrategyConfig getStrategy() { return strategy; }
    public void setStrategy(StrategyConfig strategy) { this.strategy = strategy; }

    public DisruptorConfig getDisruptor() { return disruptor; }
    public void setDisruptor(DisruptorConfig disruptor) { this.disruptor = disruptor; }

    public TimeoutsConfig getTimeouts() { return timeouts; }
    public void setTimeouts(TimeoutsConfig timeouts) { this.timeouts = timeouts; }

    public RetryConfig getRetry() { return retry; }
    public void setRetry(RetryConfig retry) { this.retry = retry; }

    public HealthCheckConfig getHealthCheck() { return healthCheck; }
    public void setHealthCheck(HealthCheckConfig healthCheck) { this.healthCheck = healthCheck; }

    public ValidationConfig getValidation() { return validation; }
    public void setValidation(ValidationConfig validation) { this.validation = validation; }

    public MetricsConfig getMetrics() { return metrics; }
    public void setMetrics(MetricsConfig metrics) { this.metrics = metrics; }

    /**
     * HTTP server configuration.
     */
    public static class ServerConfig {
        private int port = 8080;
        private String host = "0.0.0.0";
        private int backlog = 100;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getBacklog() { return backlog; }
        public void setBacklog(int backlog) { this.backlog = backlog; }
    }

    /**
     * Individual Ollama node configuration.
     */
    public static class NodeConfig {
        private String id;
        private String url;
        private Set<String> models = new HashSet<>();
        private int maxConcurrentRequests = 10;
        private int weight = 1;
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public Set<String> getModels() { return models; }
        public void setModels(Set<String> models) { this.models = models; }

        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }

        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Load balancing strategy configuration.
     */
    public static class StrategyConfig {
        private String type = "least-in-flight";
        private boolean adaptive = false;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public boolean isAdaptive() { return adaptive; }
        public void setAdaptive(boolean adaptive) { this.adaptive = adaptive; }
    }

    /**
     * LMAX Disruptor configuration.
     */
    public static class DisruptorConfig {
        private int ringBufferSize = 1024;
        private String waitStrategy = "yielding";
        private int maxGlobalInFlight = 1000;

        public int getRingBufferSize() { return ringBufferSize; }
        public void setRingBufferSize(int ringBufferSize) { this.ringBufferSize = ringBufferSize; }

        public String getWaitStrategy() { return waitStrategy; }
        public void setWaitStrategy(String waitStrategy) { this.waitStrategy = waitStrategy; }

        public int getMaxGlobalInFlight() { return maxGlobalInFlight; }
        public void setMaxGlobalInFlight(int maxGlobalInFlight) { this.maxGlobalInFlight = maxGlobalInFlight; }
    }

    /**
     * Timeout configuration.
     */
    public static class TimeoutsConfig {
        private long requestTimeoutMs = 60000;
        private long connectTimeoutMs = 10000;
        private long healthCheckTimeoutMs = 5000;

        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }

        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

        public long getHealthCheckTimeoutMs() { return healthCheckTimeoutMs; }
        public void setHealthCheckTimeoutMs(long healthCheckTimeoutMs) { this.healthCheckTimeoutMs = healthCheckTimeoutMs; }
    }

    /**
     * Retry configuration.
     */
    public static class RetryConfig {
        private int maxRetries = 3;
        private long initialBackoffMs = 100;
        private long maxBackoffMs = 5000;
        private double backoffMultiplier = 2.0;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }

        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }

        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    }

    /**
     * Health check configuration.
     */
    public static class HealthCheckConfig {
        private long intervalMs = 30000;
        private int degradedThreshold = 3;
        private int downThreshold = 6;
        private int circuitBreakerFailureThreshold = 5;
        private long circuitBreakerRecoveryMs = 30000;

        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

        public int getDegradedThreshold() { return degradedThreshold; }
        public void setDegradedThreshold(int degradedThreshold) { this.degradedThreshold = degradedThreshold; }

        public int getDownThreshold() { return downThreshold; }
        public void setDownThreshold(int downThreshold) { this.downThreshold = downThreshold; }

        public int getCircuitBreakerFailureThreshold() { return circuitBreakerFailureThreshold; }
        public void setCircuitBreakerFailureThreshold(int threshold) { this.circuitBreakerFailureThreshold = threshold; }

        public long getCircuitBreakerRecoveryMs() { return circuitBreakerRecoveryMs; }
        public void setCircuitBreakerRecoveryMs(long ms) { this.circuitBreakerRecoveryMs = ms; }
    }

    /**
     * Request validation configuration.
     */
    public static class ValidationConfig {
        private int maxPromptLength = 100000;
        private Set<String> allowedModels = new HashSet<>();

        public int getMaxPromptLength() { return maxPromptLength; }
        public void setMaxPromptLength(int maxPromptLength) { this.maxPromptLength = maxPromptLength; }

        public Set<String> getAllowedModels() { return allowedModels; }
        public void setAllowedModels(Set<String> allowedModels) { this.allowedModels = allowedModels; }
    }

    /**
     * Metrics configuration.
     */
    public static class MetricsConfig {
        private boolean enabled = true;
        private String prefix = "ollama_lb";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
    }
}
