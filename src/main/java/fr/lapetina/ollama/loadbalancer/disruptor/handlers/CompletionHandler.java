package fr.lapetina.ollama.loadbalancer.disruptor.handlers;

import com.lmax.disruptor.EventHandler;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import fr.lapetina.ollama.loadbalancer.domain.strategy.LoadBalancingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Final stage handler: handles request completion and cleanup.
 *
 * Responsibilities:
 * - Ensures caller's CompletableFuture is completed
 * - Reports success/failure to load balancing strategy for adaptive routing
 * - Clears the event for reuse
 * - Logs final request summary
 */
public final class CompletionHandler implements EventHandler<InferenceRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(CompletionHandler.class);

    private final AtomicReference<LoadBalancingStrategy> strategyRef;

    public CompletionHandler(AtomicReference<LoadBalancingStrategy> strategyRef) {
        this.strategyRef = strategyRef;
    }

    @Override
    public void onEvent(InferenceRequestEvent event, long sequence, boolean endOfBatch) {
        try {
            // Report to strategy for adaptive routing
            reportToStrategy(event);

            // Log final summary
            logCompletionSummary(event);

        } finally {
            // Clear event for reuse (important for memory efficiency)
            event.clear();
        }
    }

    private void reportToStrategy(InferenceRequestEvent event) {
        if (event.getSelectedNode() == null) {
            return;
        }

        LoadBalancingStrategy strategy = strategyRef.get();
        if (event.getResponse() != null && event.getResponse().isSuccess()) {
            long latencyMs = calculateLatencyMs(event);
            strategy.recordSuccess(event.getSelectedNode(), latencyMs);
        } else {
            strategy.recordFailure(event.getSelectedNode());
        }
    }

    private long calculateLatencyMs(InferenceRequestEvent event) {
        if (event.getAcceptedAt() == null) {
            return 0;
        }
        Instant endTime = event.getCompletedAt() != null ? event.getCompletedAt() : Instant.now();
        return Duration.between(event.getAcceptedAt(), endTime).toMillis();
    }

    private void logCompletionSummary(InferenceRequestEvent event) {
        if (event.getRequest() == null) {
            return;
        }

        String requestId = event.getRequest().requestId();
        String model = event.getRequest().model();
        String nodeId = event.getSelectedNode() != null ? event.getSelectedNode().getId() : "none";
        InferenceResponse response = event.getResponse();

        long latencyMs = calculateLatencyMs(event);

        if (response != null && response.isSuccess()) {
            log.info("Request completed: requestId={}, model={}, node={}, latencyMs={}, retries={}",
                    requestId, model, nodeId, latencyMs, event.getRetryCount());
        } else {
            log.warn("Request failed: requestId={}, model={}, node={}, latencyMs={}, " +
                            "retries={}, errorType={}, errorMessage={}",
                    requestId, model, nodeId, latencyMs, event.getRetryCount(),
                    event.getErrorType(), event.getErrorMessage());
        }
    }
}
