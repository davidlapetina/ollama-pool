package fr.lapetina.ollama.loadbalancer.disruptor.handlers;

import com.lmax.disruptor.EventHandler;
import fr.lapetina.ollama.loadbalancer.domain.event.EventState;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Third stage handler: enforces rate limiting and concurrency controls.
 *
 * Controls:
 * - Global max in-flight requests across all nodes
 * - Per-node concurrency limits (via OllamaNode.tryAcquireSlot)
 * - Request admission/rejection based on current load
 */
public final class RateLimitHandler implements EventHandler<InferenceRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(RateLimitHandler.class);

    // Threshold for warning about approaching capacity (percentage)
    private static final double CAPACITY_WARNING_THRESHOLD = 0.8;

    private final AtomicInteger globalInFlight;
    private final int maxGlobalInFlight;
    private volatile boolean capacityWarningLogged = false;

    public RateLimitHandler(int maxGlobalInFlight) {
        this.maxGlobalInFlight = maxGlobalInFlight;
        this.globalInFlight = new AtomicInteger(0);
        log.info("RateLimitHandler initialized: maxGlobalInFlight={}", maxGlobalInFlight);
    }

    @Override
    public void onEvent(InferenceRequestEvent event, long sequence, boolean endOfBatch) {
        if (event.shouldSkip()) {
            return;
        }

        if (event.getState() != EventState.NODE_SELECTED) {
            return;
        }

        String requestId = event.getRequest().requestId();
        OllamaNode node = event.getSelectedNode();

        // Check global limit
        int current = globalInFlight.get();
        if (current >= maxGlobalInFlight) {
            rejectForCapacity(event, "Global in-flight limit reached", current, maxGlobalInFlight);
            return;
        }

        // Warn if approaching capacity
        checkCapacityThreshold(current);

        // Try to acquire slot on the selected node
        if (!node.tryAcquireSlot()) {
            rejectForCapacity(event,
                    "Node " + node.getId() + " at capacity",
                    node.getInFlightRequests(),
                    node.getMaxConcurrentRequests());
            return;
        }

        // Increment global counter
        int newGlobalCount = globalInFlight.incrementAndGet();

        log.info("Request admitted: requestId={}, nodeId={}, nodeInFlight={}/{}, globalInFlight={}/{}",
                requestId,
                node.getId(),
                node.getInFlightRequests(),
                node.getMaxConcurrentRequests(),
                newGlobalCount,
                maxGlobalInFlight);
    }

    private void checkCapacityThreshold(int current) {
        double utilization = (double) current / maxGlobalInFlight;
        if (utilization >= CAPACITY_WARNING_THRESHOLD && !capacityWarningLogged) {
            log.warn("Approaching global capacity threshold: globalInFlight={}/{} ({}%)",
                    current, maxGlobalInFlight, (int) (utilization * 100));
            capacityWarningLogged = true;
        } else if (utilization < CAPACITY_WARNING_THRESHOLD * 0.9) {
            // Reset warning flag when utilization drops significantly
            capacityWarningLogged = false;
        }
    }

    private void rejectForCapacity(InferenceRequestEvent event, String reason, int current, int max) {
        event.setState(EventState.RATE_LIMITED);
        event.setError(ErrorType.CAPACITY_ERROR, reason + ": " + current + "/" + max);

        log.warn("Rate limited: requestId={}, model={}, reason={}, current={}, max={}",
                event.getRequest().requestId(),
                event.getRequest().model(),
                reason,
                current,
                max);
    }

    /**
     * Decrements counters after request completion.
     * Called by CompletionHandler.
     */
    public void releaseSlot(OllamaNode node) {
        if (node != null) {
            node.releaseSlot();
            log.debug("Node slot released: nodeId={}, nodeInFlight={}/{}",
                    node.getId(),
                    node.getInFlightRequests(),
                    node.getMaxConcurrentRequests());
        }
        int remaining = globalInFlight.decrementAndGet();
        log.debug("Global slot released: globalInFlight={}/{}", remaining, maxGlobalInFlight);
    }

    public int getGlobalInFlight() {
        return globalInFlight.get();
    }

    public int getMaxGlobalInFlight() {
        return maxGlobalInFlight;
    }
}
