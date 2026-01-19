package fr.lapetina.ollama.loadbalancer.disruptor.handlers;

import com.lmax.disruptor.EventHandler;
import fr.lapetina.ollama.loadbalancer.domain.event.EventState;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import fr.lapetina.ollama.loadbalancer.infrastructure.http.OllamaHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fourth stage handler: dispatches requests to the selected Ollama node.
 *
 * Uses async HTTP client for non-blocking dispatch.
 * Handles timeouts and circuit breaker states.
 *
 * IMPORTANT: This handler initiates async HTTP calls. The actual response
 * is handled via CompletableFuture callbacks, not synchronously in onEvent.
 */
public final class DispatchHandler implements EventHandler<InferenceRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(DispatchHandler.class);

    private final OllamaHttpClient httpClient;
    private final RateLimitHandler rateLimitHandler;
    private final long requestTimeoutMs;

    public DispatchHandler(
            OllamaHttpClient httpClient,
            RateLimitHandler rateLimitHandler,
            long requestTimeoutMs
    ) {
        this.httpClient = httpClient;
        this.rateLimitHandler = rateLimitHandler;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    @Override
    public void onEvent(InferenceRequestEvent event, long sequence, boolean endOfBatch) {
        if (event.shouldSkip()) {
            // Still need to complete the future for skipped events
            completeWithError(event);
            return;
        }

        if (event.getState() != EventState.NODE_SELECTED) {
            // If we got here without a selected node, something went wrong
            if (event.getErrorType() == null) {
                event.setError(ErrorType.INTERNAL_ERROR, "Invalid state for dispatch: " + event.getState());
            }
            completeWithError(event);
            return;
        }

        dispatchRequest(event);
    }

    private void dispatchRequest(InferenceRequestEvent event) {
        OllamaNode node = event.getSelectedNode();
        String requestId = event.getRequest().requestId();
        String model = event.getRequest().model();
        event.markDispatched();

        log.info("Dispatching request: requestId={}, model={}, nodeId={}, nodeUrl={}, timeoutMs={}",
                requestId,
                model,
                node.getId(),
                node.getBaseUrl(),
                requestTimeoutMs);

        Instant startTime = Instant.now();

        CompletableFuture<InferenceResponse> httpFuture = httpClient.sendRequest(
                node,
                event.getRequest()
        );

        // Apply timeout
        httpFuture
                .orTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> {
                    handleResponse(event, response, throwable, startTime);
                });
    }

    private void handleResponse(
            InferenceRequestEvent event,
            InferenceResponse response,
            Throwable throwable,
            Instant startTime
    ) {
        OllamaNode node = event.getSelectedNode();
        long latencyMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();

        try {
            if (throwable != null) {
                handleError(event, throwable, latencyMs);
            } else {
                handleSuccess(event, response, latencyMs);
            }
        } finally {
            // Always release the slot
            rateLimitHandler.releaseSlot(node);
        }
    }

    private void handleSuccess(
            InferenceRequestEvent event,
            InferenceResponse response,
            long latencyMs
    ) {
        OllamaNode node = event.getSelectedNode();
        String requestId = event.getRequest().requestId();
        String model = event.getRequest().model();
        node.recordSuccess();

        event.setResponse(response);
        event.setState(EventState.RESPONSE_RECEIVED);
        event.markCompleted();

        // Complete the caller's future
        CompletableFuture<InferenceResponse> future = event.getResponseFuture();
        if (future != null) {
            future.complete(response);
        }

        // Check if this was actually an error response from the HTTP layer
        if (response.errorType() != null) {
            log.warn("Request completed with error: requestId={}, model={}, nodeId={}, errorType={}, latencyMs={}",
                    requestId, model, node.getId(), response.errorType(), latencyMs);
        } else {
            log.info("Request completed successfully: requestId={}, model={}, nodeId={}, latencyMs={}",
                    requestId, model, node.getId(), latencyMs);
        }
    }

    private void handleError(
            InferenceRequestEvent event,
            Throwable throwable,
            long latencyMs
    ) {
        OllamaNode node = event.getSelectedNode();
        String requestId = event.getRequest().requestId();
        String model = event.getRequest().model();
        int failures = node.recordFailure();

        ErrorType errorType = classifyError(throwable);
        String errorMessage = throwable.getMessage();

        event.markFailed(errorType, errorMessage);

        // Create error response
        InferenceResponse errorResponse = InferenceResponse.error(
                requestId,
                model,
                errorType,
                errorMessage,
                event.getRequest().createdAt()
        );
        event.setResponse(errorResponse);

        // Complete the caller's future with error response
        CompletableFuture<InferenceResponse> future = event.getResponseFuture();
        if (future != null) {
            future.complete(errorResponse);
        }

        log.error("Request failed: requestId={}, model={}, nodeId={}, errorType={}, error={}, consecutiveFailures={}, latencyMs={}",
                requestId,
                model,
                node.getId(),
                errorType,
                errorMessage,
                failures,
                latencyMs);
    }

    private void completeWithError(InferenceRequestEvent event) {
        CompletableFuture<InferenceResponse> future = event.getResponseFuture();
        if (future == null) {
            return;
        }

        // Create error response from event's error info
        ErrorType errorType = event.getErrorType();
        if (errorType == null) {
            errorType = ErrorType.INTERNAL_ERROR;
        }

        String requestId = event.getRequest() != null ? event.getRequest().requestId() : "unknown";
        String model = event.getRequest() != null ? event.getRequest().model() : "unknown";

        InferenceResponse errorResponse = InferenceResponse.error(
                requestId,
                model,
                errorType,
                event.getErrorMessage(),
                event.getRequest() != null ? event.getRequest().createdAt() : Instant.now()
        );

        log.warn("Completing request with pre-dispatch error: requestId={}, model={}, errorType={}, error={}",
                requestId, model, errorType, event.getErrorMessage());

        future.complete(errorResponse);
    }

    private ErrorType classifyError(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.TimeoutException) {
            return ErrorType.TIMEOUT;
        }
        if (throwable instanceof java.net.ConnectException
                || throwable instanceof java.net.http.HttpConnectTimeoutException) {
            return ErrorType.NODE_ERROR;
        }
        if (throwable instanceof java.io.IOException) {
            return ErrorType.NODE_ERROR;
        }
        return ErrorType.INTERNAL_ERROR;
    }
}
