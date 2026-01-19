package fr.lapetina.ollama.loadbalancer.disruptor;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import fr.lapetina.ollama.loadbalancer.disruptor.exception.BackpressureException;
import fr.lapetina.ollama.loadbalancer.disruptor.handlers.*;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEventFactory;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import fr.lapetina.ollama.loadbalancer.domain.strategy.LoadBalancingStrategy;
import fr.lapetina.ollama.loadbalancer.infrastructure.config.LoadBalancerConfig;
import fr.lapetina.ollama.loadbalancer.infrastructure.health.NodeRegistry;
import fr.lapetina.ollama.loadbalancer.infrastructure.http.OllamaHttpClient;
import fr.lapetina.ollama.loadbalancer.infrastructure.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main Disruptor pipeline for processing inference requests.
 *
 * WHY DISRUPTOR OVER ExecutorService:
 *
 * 1. MEMORY EFFICIENCY: Disruptor pre-allocates a fixed ring buffer, eliminating
 *    object allocation during request processing. ExecutorService creates new
 *    Runnable/Callable objects and queue nodes for each task.
 *
 * 2. MECHANICAL SYMPATHY: The ring buffer is designed for CPU cache efficiency.
 *    Sequential access patterns and pre-allocated memory reduce cache misses
 *    compared to linked-list based queues in ExecutorService.
 *
 * 3. LOCK-FREE PUBLISHING: Multi-producer publishing uses CAS operations instead
 *    of locks. ExecutorService's BlockingQueue typically uses locks.
 *
 * 4. PIPELINE PROCESSING: Handlers can be organized in sequences, allowing
 *    parallel stages followed by serial stages. This maps naturally to our
 *    validate -> select -> rate-limit -> dispatch -> metrics -> complete flow.
 *
 * 5. BATCHING: The endOfBatch flag allows handlers to optimize batch operations
 *    (e.g., flush metrics, batch HTTP requests if supported).
 *
 * 6. BACKPRESSURE: When the ring buffer is full, publishers immediately know
 *    and can reject/shed load. ExecutorService queues may grow unbounded or
 *    block the caller.
 *
 * PRODUCER TYPE CHOICE: MULTI
 *
 * We use MULTI producer because requests come from multiple HTTP handler threads
 * concurrently. While SINGLE producer is faster, it would require funneling all
 * requests through a single thread, creating a bottleneck.
 *
 * WAIT STRATEGY CHOICE: Configurable (default YieldingWaitStrategy)
 *
 * - YieldingWaitStrategy: Low latency, burns CPU in a yield loop. Good for
 *   dedicated machines with available cores.
 * - BlockingWaitStrategy: Uses locks, higher latency but CPU friendly. Good
 *   for shared/cloud environments.
 */
public final class DisruptorPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DisruptorPipeline.class);

    private final Disruptor<InferenceRequestEvent> disruptor;
    private final RingBuffer<InferenceRequestEvent> ringBuffer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Handlers (exposed for testing and configuration)
    private final ValidationHandler validationHandler;
    private final NodeSelectionHandler nodeSelectionHandler;
    private final RateLimitHandler rateLimitHandler;
    private final DispatchHandler dispatchHandler;
    private final MetricsHandler metricsHandler;
    private final CompletionHandler completionHandler;

    // Strategy reference shared with NodeSelectionHandler and CompletionHandler
    private final AtomicReference<LoadBalancingStrategy> strategyRef;

    private final int maxRetries;

    private DisruptorPipeline(Builder builder) {
        this.maxRetries = builder.maxRetries;

        // Create thread factory for Disruptor consumers
        ThreadFactory threadFactory = new DisruptorThreadFactory("disruptor-handler");

        // Select wait strategy based on configuration
        WaitStrategy waitStrategy = createWaitStrategy(builder.waitStrategy);

        // Create the Disruptor
        this.disruptor = new Disruptor<>(
                new InferenceRequestEventFactory(),
                builder.ringBufferSize,
                threadFactory,
                ProducerType.MULTI, // Multiple HTTP threads publish concurrently
                waitStrategy
        );

        // Create handlers
        this.strategyRef = new AtomicReference<>(builder.initialStrategy);
        this.validationHandler = new ValidationHandler(builder.allowedModels, builder.maxPromptLength);
        this.nodeSelectionHandler = new NodeSelectionHandler(builder.nodeRegistry, builder.initialStrategy);
        this.rateLimitHandler = new RateLimitHandler(builder.maxGlobalInFlight);
        this.dispatchHandler = new DispatchHandler(
                builder.httpClient,
                rateLimitHandler,
                builder.requestTimeoutMs
        );
        this.metricsHandler = new MetricsHandler(builder.metricsRegistry);
        this.completionHandler = new CompletionHandler(strategyRef);

        // Wire up the handler pipeline
        // Order: Validation -> Node Selection -> Rate Limit -> Dispatch -> Metrics -> Completion
        //
        // Handlers run in sequence. Each handler processes all events before the next
        // handler in the chain sees them.
        disruptor
                .handleEventsWith(validationHandler)
                .then(nodeSelectionHandler)
                .then(rateLimitHandler)
                .then(dispatchHandler)
                .then(metricsHandler)
                .then(completionHandler);

        // Set up exception handler
        disruptor.setDefaultExceptionHandler(new DisruptorExceptionHandler());

        this.ringBuffer = disruptor.getRingBuffer();

        log.info("DisruptorPipeline created: ringBufferSize={}, waitStrategy={}, maxRetries={}",
                builder.ringBufferSize, builder.waitStrategy, builder.maxRetries);
    }

    /**
     * Starts the Disruptor processing.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            disruptor.start();
            log.info("DisruptorPipeline started");
        }
    }

    /**
     * Submits a request for processing.
     *
     * @param request The inference request
     * @return CompletableFuture that will complete with the response
     * @throws BackpressureException if the ring buffer is full
     */
    public CompletableFuture<InferenceResponse> submit(InferenceRequest request) {
        if (!running.get()) {
            CompletableFuture<InferenceResponse> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Pipeline not running"));
            return future;
        }

        CompletableFuture<InferenceResponse> responseFuture = new CompletableFuture<>();

        // Try to claim a slot in the ring buffer
        long sequence;
        try {
            sequence = ringBuffer.tryNext();
        } catch (com.lmax.disruptor.InsufficientCapacityException e) {
            throw new BackpressureException(
                    BackpressureException.BackpressureReason.RING_BUFFER_FULL,
                    "Ring buffer full, remaining capacity: " + ringBuffer.remainingCapacity()
            );
        }

        try {
            InferenceRequestEvent event = ringBuffer.get(sequence);
            event.initialize(request, responseFuture, maxRetries);
        } finally {
            ringBuffer.publish(sequence);
        }

        log.debug("Request submitted: requestId={}, sequence={}",
                request.requestId(), sequence);

        return responseFuture;
    }

    /**
     * Submits a request with a timeout for publishing to the ring buffer.
     *
     * @param request The inference request
     * @param timeout Timeout for claiming ring buffer slot
     * @param unit    Time unit
     * @return CompletableFuture that will complete with the response
     * @throws BackpressureException if the ring buffer is full after timeout
     */
    public CompletableFuture<InferenceResponse> submit(
            InferenceRequest request,
            long timeout,
            TimeUnit unit
    ) {
        // For now, delegate to simple submit
        // In production, could implement timed tryNext
        return submit(request);
    }

    /**
     * Changes the load balancing strategy at runtime.
     */
    public void setStrategy(LoadBalancingStrategy strategy) {
        LoadBalancingStrategy old = strategyRef.getAndSet(strategy);
        nodeSelectionHandler.setStrategy(strategy);
        log.info("Load balancing strategy changed: {} -> {}",
                old.getName(), strategy.getName());
    }

    public LoadBalancingStrategy getStrategy() {
        return strategyRef.get();
    }

    /**
     * Returns current ring buffer remaining capacity.
     */
    public long getRemainingCapacity() {
        return ringBuffer.remainingCapacity();
    }

    /**
     * Returns current global in-flight request count.
     */
    public int getGlobalInFlight() {
        return rateLimitHandler.getGlobalInFlight();
    }

    /**
     * Gracefully shuts down the pipeline.
     */
    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down DisruptorPipeline...");
            try {
                disruptor.shutdown(30, TimeUnit.SECONDS);
                log.info("DisruptorPipeline shut down gracefully");
            } catch (TimeoutException e) {
                log.warn("DisruptorPipeline shutdown timed out, halting...");
                disruptor.halt();
            }
        }
    }

    private WaitStrategy createWaitStrategy(String name) {
        return switch (name.toLowerCase()) {
            case "blocking" -> new BlockingWaitStrategy();
            case "yielding" -> new YieldingWaitStrategy();
            case "busy-spin" -> new com.lmax.disruptor.BusySpinWaitStrategy();
            case "sleeping" -> new com.lmax.disruptor.SleepingWaitStrategy();
            default -> {
                log.warn("Unknown wait strategy '{}', using YieldingWaitStrategy", name);
                yield new YieldingWaitStrategy();
            }
        };
    }

    // Getters for testing
    public ValidationHandler getValidationHandler() {
        return validationHandler;
    }

    public NodeSelectionHandler getNodeSelectionHandler() {
        return nodeSelectionHandler;
    }

    public RateLimitHandler getRateLimitHandler() {
        return rateLimitHandler;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Thread factory for Disruptor consumer threads.
     */
    private static class DisruptorThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        DisruptorThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }

    /**
     * Exception handler for Disruptor.
     */
    private static class DisruptorExceptionHandler
            implements com.lmax.disruptor.ExceptionHandler<InferenceRequestEvent> {

        private static final Logger log = LoggerFactory.getLogger(DisruptorExceptionHandler.class);

        @Override
        public void handleEventException(Throwable ex, long sequence, InferenceRequestEvent event) {
            log.error("Exception in event handler: sequence={}, event={}", sequence, event, ex);

            // Try to complete the future with error
            if (event.getResponseFuture() != null && !event.getResponseFuture().isDone()) {
                event.getResponseFuture().completeExceptionally(ex);
            }
        }

        @Override
        public void handleOnStartException(Throwable ex) {
            log.error("Exception during Disruptor start", ex);
        }

        @Override
        public void handleOnShutdownException(Throwable ex) {
            log.error("Exception during Disruptor shutdown", ex);
        }
    }

    /**
     * Builder for DisruptorPipeline.
     */
    public static final class Builder {
        private int ringBufferSize = 1024;
        private String waitStrategy = "yielding";
        private int maxRetries = 3;
        private int maxGlobalInFlight = 1000;
        private long requestTimeoutMs = 60_000;
        private int maxPromptLength = 100_000;
        private Set<String> allowedModels = Set.of();
        private NodeRegistry nodeRegistry;
        private OllamaHttpClient httpClient;
        private MetricsRegistry metricsRegistry;
        private LoadBalancingStrategy initialStrategy;

        public Builder ringBufferSize(int size) {
            // Must be power of 2
            if (Integer.bitCount(size) != 1) {
                throw new IllegalArgumentException("Ring buffer size must be power of 2");
            }
            this.ringBufferSize = size;
            return this;
        }

        public Builder waitStrategy(String strategy) {
            this.waitStrategy = strategy;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder maxGlobalInFlight(int max) {
            this.maxGlobalInFlight = max;
            return this;
        }

        public Builder requestTimeoutMs(long timeoutMs) {
            this.requestTimeoutMs = timeoutMs;
            return this;
        }

        public Builder maxPromptLength(int maxLength) {
            this.maxPromptLength = maxLength;
            return this;
        }

        public Builder allowedModels(Set<String> models) {
            this.allowedModels = models;
            return this;
        }

        public Builder nodeRegistry(NodeRegistry registry) {
            this.nodeRegistry = registry;
            return this;
        }

        public Builder httpClient(OllamaHttpClient client) {
            this.httpClient = client;
            return this;
        }

        public Builder metricsRegistry(MetricsRegistry registry) {
            this.metricsRegistry = registry;
            return this;
        }

        public Builder initialStrategy(LoadBalancingStrategy strategy) {
            this.initialStrategy = strategy;
            return this;
        }

        public Builder fromConfig(LoadBalancerConfig config) {
            this.ringBufferSize = config.getDisruptor().getRingBufferSize();
            this.waitStrategy = config.getDisruptor().getWaitStrategy();
            this.maxRetries = config.getRetry().getMaxRetries();
            this.maxGlobalInFlight = config.getDisruptor().getMaxGlobalInFlight();
            this.requestTimeoutMs = config.getTimeouts().getRequestTimeoutMs();
            this.maxPromptLength = config.getValidation().getMaxPromptLength();
            this.allowedModels = config.getValidation().getAllowedModels();
            return this;
        }

        public DisruptorPipeline build() {
            if (nodeRegistry == null) {
                throw new IllegalStateException("NodeRegistry is required");
            }
            if (httpClient == null) {
                throw new IllegalStateException("OllamaHttpClient is required");
            }
            if (metricsRegistry == null) {
                throw new IllegalStateException("MetricsRegistry is required");
            }
            if (initialStrategy == null) {
                throw new IllegalStateException("Initial LoadBalancingStrategy is required");
            }
            return new DisruptorPipeline(this);
        }
    }
}
