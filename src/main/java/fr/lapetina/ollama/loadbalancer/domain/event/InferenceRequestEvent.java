package fr.lapetina.ollama.loadbalancer.domain.event;

import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Event object for the LMAX Disruptor ring buffer.
 *
 * This is a mutable holder that gets reused across the ring buffer.
 * Each handler stage updates the event as it progresses through the pipeline.
 *
 * IMPORTANT: This class is intentionally mutable for Disruptor performance.
 * It should never be accessed outside the Disruptor pipeline handlers.
 */
public final class InferenceRequestEvent {

    // Immutable request data
    private InferenceRequest request;

    // Mutable state tracking
    private EventState state;
    private OllamaNode selectedNode;
    private InferenceResponse response;
    private ErrorType errorType;
    private String errorMessage;
    private int retryCount;
    private int maxRetries;

    // Timing
    private Instant acceptedAt;
    private Instant validatedAt;
    private Instant nodeSelectedAt;
    private Instant dispatchedAt;
    private Instant completedAt;

    // Callback for async completion
    private CompletableFuture<InferenceResponse> responseFuture;

    // Sequence number (set by Disruptor)
    private long sequence;

    /**
     * Clears the event for reuse.
     * Called by the EventFactory and at the end of processing.
     */
    public void clear() {
        this.request = null;
        this.state = null;
        this.selectedNode = null;
        this.response = null;
        this.errorType = null;
        this.errorMessage = null;
        this.retryCount = 0;
        this.maxRetries = 3;
        this.acceptedAt = null;
        this.validatedAt = null;
        this.nodeSelectedAt = null;
        this.dispatchedAt = null;
        this.completedAt = null;
        this.responseFuture = null;
        this.sequence = -1;
    }

    /**
     * Initializes the event with a new request.
     */
    public void initialize(
            InferenceRequest request,
            CompletableFuture<InferenceResponse> responseFuture,
            int maxRetries
    ) {
        clear();
        this.request = request;
        this.responseFuture = responseFuture;
        this.maxRetries = maxRetries;
        this.state = EventState.CREATED;
        this.acceptedAt = Instant.now();
    }

    // Getters
    public InferenceRequest getRequest() {
        return request;
    }

    public EventState getState() {
        return state;
    }

    public OllamaNode getSelectedNode() {
        return selectedNode;
    }

    public InferenceResponse getResponse() {
        return response;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public Instant getNodeSelectedAt() {
        return nodeSelectedAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public CompletableFuture<InferenceResponse> getResponseFuture() {
        return responseFuture;
    }

    public long getSequence() {
        return sequence;
    }

    // Setters for handler updates
    public void setState(EventState state) {
        this.state = state;
    }

    public void setSelectedNode(OllamaNode node) {
        this.selectedNode = node;
        this.nodeSelectedAt = Instant.now();
    }

    public void setResponse(InferenceResponse response) {
        this.response = response;
    }

    public void setError(ErrorType errorType, String message) {
        this.errorType = errorType;
        this.errorMessage = message;
    }

    public void markValidated() {
        this.state = EventState.VALIDATED;
        this.validatedAt = Instant.now();
    }

    public void markDispatched() {
        this.state = EventState.DISPATCHED;
        this.dispatchedAt = Instant.now();
    }

    public void markCompleted() {
        this.state = EventState.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(ErrorType errorType, String message) {
        this.state = EventState.FAILED;
        this.errorType = errorType;
        this.errorMessage = message;
        this.completedAt = Instant.now();
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    /**
     * Checks if the event has reached a terminal state.
     */
    public boolean isTerminal() {
        return state == EventState.COMPLETED
            || state == EventState.FAILED
            || state == EventState.TIMED_OUT
            || state == EventState.CANCELLED;
    }

    /**
     * Checks if processing should skip remaining handlers.
     */
    public boolean shouldSkip() {
        return state == EventState.VALIDATION_FAILED
            || state == EventState.NO_NODE_AVAILABLE
            || state == EventState.RATE_LIMITED
            || isTerminal();
    }

    @Override
    public String toString() {
        return "InferenceRequestEvent{" +
                "requestId=" + (request != null ? request.requestId() : "null") +
                ", state=" + state +
                ", node=" + (selectedNode != null ? selectedNode.getId() : "null") +
                ", retry=" + retryCount + "/" + maxRetries +
                ", seq=" + sequence +
                '}';
    }
}
