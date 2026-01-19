package fr.lapetina.ollama.loadbalancer.disruptor.handlers;

import com.lmax.disruptor.EventHandler;
import fr.lapetina.ollama.loadbalancer.domain.event.EventState;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.infrastructure.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;

/**
 * Fifth stage handler: records metrics and tracing information.
 *
 * Records:
 * - Request count by model, node, status
 * - Latency histograms
 * - Error rates
 * - Sets MDC context for structured logging
 */
public final class MetricsHandler implements EventHandler<InferenceRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(MetricsHandler.class);

    private final MetricsRegistry metricsRegistry;

    public MetricsHandler(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    public void onEvent(InferenceRequestEvent event, long sequence, boolean endOfBatch) {
        setupMDC(event);

        try {
            recordMetrics(event);
        } finally {
            clearMDC();
        }
    }

    private void setupMDC(InferenceRequestEvent event) {
        if (event.getRequest() != null) {
            MDC.put("requestId", event.getRequest().requestId());
            MDC.put("correlationId", event.getRequest().correlationId());
            MDC.put("model", event.getRequest().model());
        }
        if (event.getSelectedNode() != null) {
            MDC.put("nodeId", event.getSelectedNode().getId());
        }
        MDC.put("eventState", event.getState() != null ? event.getState().name() : "UNKNOWN");
    }

    private void clearMDC() {
        MDC.remove("requestId");
        MDC.remove("correlationId");
        MDC.remove("model");
        MDC.remove("nodeId");
        MDC.remove("eventState");
    }

    private void recordMetrics(InferenceRequestEvent event) {
        String model = event.getRequest() != null ? event.getRequest().model() : "unknown";
        String nodeId = event.getSelectedNode() != null ? event.getSelectedNode().getId() : "none";
        EventState state = event.getState();

        // Record request count
        metricsRegistry.incrementRequestCount(model, nodeId, state);

        // Record latency for completed requests
        if (event.isTerminal() && event.getAcceptedAt() != null) {
            Duration totalLatency = Duration.between(event.getAcceptedAt(), Instant.now());
            metricsRegistry.recordLatency(model, nodeId, totalLatency);
        }

        // Record stage-specific timings
        if (event.getValidatedAt() != null && event.getAcceptedAt() != null) {
            Duration validationTime = Duration.between(event.getAcceptedAt(), event.getValidatedAt());
            metricsRegistry.recordStageLatency("validation", validationTime);
        }

        if (event.getNodeSelectedAt() != null && event.getValidatedAt() != null) {
            Duration selectionTime = Duration.between(event.getValidatedAt(), event.getNodeSelectedAt());
            metricsRegistry.recordStageLatency("node_selection", selectionTime);
        }

        if (event.getDispatchedAt() != null && event.getNodeSelectedAt() != null) {
            Duration queueTime = Duration.between(event.getNodeSelectedAt(), event.getDispatchedAt());
            metricsRegistry.recordStageLatency("queue", queueTime);
        }

        // Record errors
        if (event.getErrorType() != null) {
            metricsRegistry.incrementErrorCount(model, nodeId, event.getErrorType());

            log.warn("Request error recorded: model={}, node={}, errorType={}, message={}",
                    model, nodeId, event.getErrorType(), event.getErrorMessage());
        }
    }
}
