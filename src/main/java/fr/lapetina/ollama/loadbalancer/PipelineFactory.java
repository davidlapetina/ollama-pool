package fr.lapetina.ollama.loadbalancer;

import fr.lapetina.ollama.loadbalancer.disruptor.DisruptorPipeline;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import fr.lapetina.ollama.loadbalancer.domain.strategy.LoadBalancingStrategy;
import fr.lapetina.ollama.loadbalancer.domain.strategy.StrategyFactory;
import fr.lapetina.ollama.loadbalancer.infrastructure.config.ConfigLoader;
import fr.lapetina.ollama.loadbalancer.infrastructure.config.LoadBalancerConfig;
import fr.lapetina.ollama.loadbalancer.infrastructure.health.NodeHealthChecker;
import fr.lapetina.ollama.loadbalancer.infrastructure.health.NodeRegistry;
import fr.lapetina.ollama.loadbalancer.infrastructure.http.OllamaHttpClient;
import fr.lapetina.ollama.loadbalancer.infrastructure.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Factory for creating fully-wired pipeline instances from configuration.
 * This is the primary entry point for obtaining a configured DisruptorPipeline.
 *
 * <p>Usage:
 * <pre>{@code
 * try (PipelineFactory factory = PipelineFactory.create("config.yaml")) {
 *     DisruptorPipeline pipeline = factory.getPipeline();
 *     // use pipeline...
 * }
 * }</pre>
 */
public class PipelineFactory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PipelineFactory.class);

    private final ConfigLoader configLoader;
    private final LoadBalancerConfig config;
    private final NodeRegistry nodeRegistry;
    private final MetricsRegistry metricsRegistry;
    private final OllamaHttpClient httpClient;
    private final NodeHealthChecker healthChecker;
    private final DisruptorPipeline pipeline;

    protected PipelineFactory(String configPath, OllamaHttpClient httpClientOverride) {
        log.info("Initializing PipelineFactory from config: {}", configPath);

        // Load configuration
        this.configLoader = new ConfigLoader(configPath);
        this.config = configLoader.load();

        // Initialize metrics
        this.metricsRegistry = new MetricsRegistry(config.getMetrics().getPrefix());

        // Initialize node registry
        this.nodeRegistry = new NodeRegistry();
        loadNodes();

        // Initialize HTTP client (allow override for testing)
        this.httpClient = httpClientOverride != null ? httpClientOverride : createHttpClient();

        // Initialize health checker
        this.healthChecker = new NodeHealthChecker(
                nodeRegistry,
                httpClient,
                Duration.ofMillis(config.getHealthCheck().getIntervalMs()),
                config.getHealthCheck().getDegradedThreshold()
        );

        // Create strategy
        LoadBalancingStrategy strategy = StrategyFactory.createOrDefault(
                config.getStrategy().getType(),
                StrategyFactory.create("least-in-flight").orElseThrow()
        );
        log.info("Using load balancing strategy: {}", strategy.getName());

        // Build pipeline
        this.pipeline = DisruptorPipeline.builder()
                .fromConfig(config)
                .nodeRegistry(nodeRegistry)
                .httpClient(httpClient)
                .metricsRegistry(metricsRegistry)
                .initialStrategy(strategy)
                .build();

        // Register config change listener
        configLoader.addListener(this::onConfigChanged);

        // Register node metrics
        registerNodeMetrics();

        log.info("PipelineFactory initialized with {} nodes", nodeRegistry.size());
    }

    /**
     * Creates a factory from the specified configuration file.
     */
    public static PipelineFactory create(String configPath) {
        return new PipelineFactory(configPath, null);
    }

    /**
     * Creates a factory from the default configuration (config.yaml).
     */
    public static PipelineFactory create() {
        return create("config.yaml");
    }

    /**
     * Starts the pipeline and health checker.
     */
    public PipelineFactory start() {
        pipeline.start();
        healthChecker.start();
        configLoader.startWatching();
        log.info("Pipeline started");
        return this;
    }

    public DisruptorPipeline getPipeline() {
        return pipeline;
    }

    public NodeRegistry getNodeRegistry() {
        return nodeRegistry;
    }

    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }

    public OllamaHttpClient getHttpClient() {
        return httpClient;
    }

    public LoadBalancerConfig getConfig() {
        return config;
    }

    public ConfigLoader getConfigLoader() {
        return configLoader;
    }

    private OllamaHttpClient createHttpClient() {
        return new OllamaHttpClient(
                Duration.ofMillis(config.getTimeouts().getConnectTimeoutMs()),
                config.getHealthCheck().getCircuitBreakerFailureThreshold(),
                Duration.ofMillis(config.getHealthCheck().getCircuitBreakerRecoveryMs())
        );
    }

    private void loadNodes() {
        for (LoadBalancerConfig.NodeConfig nodeConfig : config.getNodes()) {
            OllamaNode node = OllamaNode.builder()
                    .id(nodeConfig.getId())
                    .baseUrl(nodeConfig.getUrl())
                    .models(nodeConfig.getModels())
                    .maxConcurrentRequests(nodeConfig.getMaxConcurrentRequests())
                    .weight(nodeConfig.getWeight())
                    .enabled(nodeConfig.isEnabled())
                    .build();
            nodeRegistry.registerNode(node);
            log.debug("Registered node: {}", node);
        }
    }

    private void registerNodeMetrics() {
        for (OllamaNode node : nodeRegistry.getAllNodes()) {
            metricsRegistry.registerNodeInFlight(node.getId(), node::getInFlightRequests);
            metricsRegistry.registerNodeHealth(node.getId(), () -> {
                return switch (node.getHealth()) {
                    case UP -> 2;
                    case DEGRADED -> 1;
                    case DOWN -> 0;
                };
            });
        }
    }

    private void onConfigChanged(LoadBalancerConfig oldConfig, LoadBalancerConfig newConfig) {
        log.info("Configuration changed, applying updates...");

        // Reload nodes
        nodeRegistry.clear();
        for (LoadBalancerConfig.NodeConfig nodeConfig : newConfig.getNodes()) {
            OllamaNode node = OllamaNode.builder()
                    .id(nodeConfig.getId())
                    .baseUrl(nodeConfig.getUrl())
                    .models(nodeConfig.getModels())
                    .maxConcurrentRequests(nodeConfig.getMaxConcurrentRequests())
                    .weight(nodeConfig.getWeight())
                    .enabled(nodeConfig.isEnabled())
                    .build();
            nodeRegistry.registerNode(node);
        }
        registerNodeMetrics();

        // Update strategy if changed
        if (oldConfig == null ||
                !oldConfig.getStrategy().getType().equals(newConfig.getStrategy().getType())) {
            LoadBalancingStrategy newStrategy = StrategyFactory.createOrDefault(
                    newConfig.getStrategy().getType(),
                    pipeline.getStrategy()
            );
            pipeline.setStrategy(newStrategy);
        }

        log.info("Configuration updates applied");
    }

    @Override
    public void close() {
        log.info("Shutting down PipelineFactory...");

        try {
            healthChecker.close();
        } catch (Exception e) {
            log.warn("Error closing health checker", e);
        }

        try {
            pipeline.close();
        } catch (Exception e) {
            log.warn("Error closing pipeline", e);
        }

        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Error closing HTTP client", e);
        }

        try {
            metricsRegistry.close();
        } catch (Exception e) {
            log.warn("Error closing metrics registry", e);
        }

        try {
            configLoader.close();
        } catch (Exception e) {
            log.warn("Error closing config loader", e);
        }

        log.info("PipelineFactory shut down");
    }
}
