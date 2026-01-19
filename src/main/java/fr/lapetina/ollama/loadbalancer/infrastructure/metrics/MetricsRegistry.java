package fr.lapetina.ollama.loadbalancer.infrastructure.metrics;

import fr.lapetina.ollama.loadbalancer.domain.event.EventState;
import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized metrics registry using Micrometer.
 *
 * Provides:
 * - Request latency histograms per model and node
 * - In-flight request gauges
 * - Error counters by type
 * - JVM and system metrics
 * - Prometheus exposition
 */
public final class MetricsRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistry.class);

    private final PrometheusMeterRegistry registry;
    private final String prefix;

    // Cache for dynamic meters
    private final ConcurrentHashMap<String, Timer> latencyTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> stageTimers = new ConcurrentHashMap<>();

    // Global gauges
    private final AtomicInteger globalInFlight = new AtomicInteger(0);
    private final AtomicInteger ringBufferRemaining = new AtomicInteger(0);
    private final AtomicInteger activeNodes = new AtomicInteger(0);

    public MetricsRegistry(String prefix) {
        this.prefix = prefix;
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Register JVM metrics
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        // Register global gauges
        Gauge.builder(prefix + "_inflight_requests_total", globalInFlight, AtomicInteger::get)
                .description("Total number of in-flight requests")
                .register(registry);

        Gauge.builder(prefix + "_ringbuffer_remaining", ringBufferRemaining, AtomicInteger::get)
                .description("Remaining capacity in the ring buffer")
                .register(registry);

        Gauge.builder(prefix + "_active_nodes", activeNodes, AtomicInteger::get)
                .description("Number of active nodes")
                .register(registry);

        log.info("MetricsRegistry initialized with prefix: {}", prefix);
    }

    public MetricsRegistry() {
        this("ollama_lb");
    }

    /**
     * Increments the request counter for a model/node/state combination.
     */
    public void incrementRequestCount(String model, String nodeId, EventState state) {
        String key = model + ":" + nodeId + ":" + state.name();
        requestCounters.computeIfAbsent(key, k ->
                Counter.builder(prefix + "_requests_total")
                        .description("Total number of requests")
                        .tag("model", model)
                        .tag("node", nodeId)
                        .tag("state", state.name())
                        .register(registry)
        ).increment();
    }

    /**
     * Records request latency.
     */
    public void recordLatency(String model, String nodeId, Duration latency) {
        String key = model + ":" + nodeId;
        latencyTimers.computeIfAbsent(key, k ->
                Timer.builder(prefix + "_request_latency")
                        .description("Request latency")
                        .tag("model", model)
                        .tag("node", nodeId)
                        .publishPercentileHistogram()
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(registry)
        ).record(latency);
    }

    /**
     * Records stage-specific latency (validation, selection, etc.).
     */
    public void recordStageLatency(String stage, Duration latency) {
        stageTimers.computeIfAbsent(stage, k ->
                Timer.builder(prefix + "_stage_latency")
                        .description("Pipeline stage latency")
                        .tag("stage", stage)
                        .publishPercentileHistogram()
                        .register(registry)
        ).record(latency);
    }

    /**
     * Increments error counter.
     */
    public void incrementErrorCount(String model, String nodeId, ErrorType errorType) {
        String key = model + ":" + nodeId + ":" + errorType.name();
        errorCounters.computeIfAbsent(key, k ->
                Counter.builder(prefix + "_errors_total")
                        .description("Total number of errors")
                        .tag("model", model)
                        .tag("node", nodeId)
                        .tag("type", errorType.name())
                        .register(registry)
        ).increment();
    }

    /**
     * Registers a gauge for node in-flight requests.
     */
    public void registerNodeInFlight(String nodeId, java.util.function.Supplier<Number> valueSupplier) {
        Gauge.builder(prefix + "_node_inflight", valueSupplier, s -> s.get().doubleValue())
                .description("In-flight requests per node")
                .tag("node", nodeId)
                .register(registry);
    }

    /**
     * Registers a gauge for node health status.
     */
    public void registerNodeHealth(String nodeId, java.util.function.Supplier<Number> healthValue) {
        Gauge.builder(prefix + "_node_health", healthValue, s -> s.get().doubleValue())
                .description("Node health status (0=DOWN, 1=DEGRADED, 2=UP)")
                .tag("node", nodeId)
                .register(registry);
    }

    /**
     * Updates the global in-flight counter.
     */
    public void setGlobalInFlight(int value) {
        globalInFlight.set(value);
    }

    /**
     * Updates the ring buffer remaining capacity.
     */
    public void setRingBufferRemaining(int value) {
        ringBufferRemaining.set(value);
    }

    /**
     * Updates the active node count.
     */
    public void setActiveNodes(int value) {
        activeNodes.set(value);
    }

    /**
     * Returns the Prometheus scrape output.
     */
    public String scrape() {
        return registry.scrape();
    }

    /**
     * Returns the underlying Micrometer registry.
     */
    public MeterRegistry getRegistry() {
        return registry;
    }

    @Override
    public void close() {
        registry.close();
    }
}
