package fr.lapetina.ollama.loadbalancer.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for communicating with Ollama servers.
 *
 * Uses java.net.http.HttpClient for modern, non-blocking I/O.
 * Includes circuit breaker per node.
 */
public class OllamaHttpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OllamaHttpClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    private final Duration connectTimeout;
    private final int failureThreshold;
    private final Duration circuitBreakerRecoveryTimeout;

    public OllamaHttpClient(
            Duration connectTimeout,
            int failureThreshold,
            Duration circuitBreakerRecoveryTimeout
    ) {
        this.connectTimeout = connectTimeout;
        this.failureThreshold = failureThreshold;
        this.circuitBreakerRecoveryTimeout = circuitBreakerRecoveryTimeout;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public OllamaHttpClient() {
        this(Duration.ofSeconds(10), 5, Duration.ofSeconds(30));
    }

    /**
     * Sends an inference request to the specified node.
     *
     * @param node    Target Ollama node
     * @param request Inference request
     * @return CompletableFuture with the response
     */
    public CompletableFuture<InferenceResponse> sendRequest(
            OllamaNode node,
            InferenceRequest request
    ) {
        CircuitBreaker breaker = getOrCreateCircuitBreaker(node.getId());

        // Check circuit breaker
        if (!breaker.allowRequest()) {
            log.warn("Request blocked by circuit breaker: nodeId={}, requestId={}, model={}",
                    node.getId(), request.requestId(), request.model());
            return CompletableFuture.completedFuture(
                    InferenceResponse.error(
                            request.requestId(),
                            request.model(),
                            ErrorType.CIRCUIT_OPEN,
                            "Circuit breaker is open for node: " + node.getId(),
                            request.createdAt()
                    )
            );
        }

        try {
            // Build the HTTP request
            HttpRequest httpRequest = buildHttpRequest(node, request);
            Instant startTime = Instant.now();

            log.info("Sending request: nodeId={}, requestId={}, model={}, endpoint={}",
                    node.getId(), request.requestId(), request.model(), httpRequest.uri());

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> handleResponse(node, request, response, startTime, breaker))
                    .exceptionally(ex -> handleException(node, request, ex, breaker));

        } catch (Exception e) {
            log.error("Failed to build request: nodeId={}, requestId={}", node.getId(), request.requestId(), e);
            return CompletableFuture.completedFuture(
                    InferenceResponse.error(
                            request.requestId(),
                            request.model(),
                            ErrorType.CLIENT_ERROR,
                            "Failed to build request: " + e.getMessage(),
                            request.createdAt()
                    )
            );
        }
    }

    private HttpRequest buildHttpRequest(OllamaNode node, InferenceRequest request) throws IOException {
        URI uri = buildUri(node, request);
        String body = buildRequestBody(request);

        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request.requestId())
                .header("X-Correlation-ID", request.correlationId())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private URI buildUri(OllamaNode node, InferenceRequest request) {
        String basePath = node.getBaseUrl().toString();
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }

        // Use /api/chat for messages, /api/generate for prompt
        String endpoint = (request.messages() != null && !request.messages().isEmpty())
                ? "api/chat"
                : "api/generate";

        return URI.create(basePath + endpoint);
    }

    private String buildRequestBody(InferenceRequest request) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("stream", request.stream());

        if (request.messages() != null && !request.messages().isEmpty()) {
            // Chat format
            List<Map<String, String>> messages = request.messages().stream()
                    .map(m -> Map.of("role", m.role(), "content", m.content()))
                    .toList();
            body.put("messages", messages);
        } else {
            // Generate format
            body.put("prompt", request.prompt());
        }

        // Add images for vision models
        if (request.images() != null && !request.images().isEmpty()) {
            body.put("images", request.images());
        }

        if (request.options() != null && !request.options().isEmpty()) {
            body.put("options", request.options());
        }

        return objectMapper.writeValueAsString(body);
    }

    private InferenceResponse handleResponse(
            OllamaNode node,
            InferenceRequest request,
            HttpResponse<String> response,
            Instant startTime,
            CircuitBreaker breaker
    ) {
        Duration totalDuration = Duration.between(startTime, Instant.now());
        int statusCode = response.statusCode();

        if (statusCode >= 200 && statusCode < 300) {
            breaker.recordSuccess();
            log.info("Request successful: nodeId={}, requestId={}, model={}, status={}, latencyMs={}",
                    node.getId(), request.requestId(), request.model(), statusCode, totalDuration.toMillis());
            return parseSuccessResponse(node, request, response.body(), totalDuration);
        } else {
            breaker.recordFailure();
            log.warn("Request failed with HTTP error: nodeId={}, requestId={}, model={}, status={}, latencyMs={}",
                    node.getId(), request.requestId(), request.model(), statusCode, totalDuration.toMillis());
            return parseErrorResponse(node, request, response, totalDuration);
        }
    }

    private InferenceResponse parseSuccessResponse(
            OllamaNode node,
            InferenceRequest request,
            String body,
            Duration totalDuration
    ) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(body, Map.class);

            String responseText = null;
            if (responseMap.containsKey("response")) {
                responseText = (String) responseMap.get("response");
            } else if (responseMap.containsKey("message")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) responseMap.get("message");
                if (message != null) {
                    responseText = (String) message.get("content");
                }
            }

            return InferenceResponse.builder()
                    .requestId(request.requestId())
                    .model(request.model())
                    .response(responseText)
                    .done(true)
                    .nodeId(node.getId())
                    .createdAt(request.createdAt())
                    .completedAt(Instant.now())
                    .totalDuration(totalDuration)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse response: nodeId={}, requestId={}", node.getId(), request.requestId(), e);
            return InferenceResponse.error(
                    request.requestId(),
                    request.model(),
                    ErrorType.INTERNAL_ERROR,
                    "Failed to parse response: " + e.getMessage(),
                    request.createdAt()
            );
        }
    }

    private InferenceResponse parseErrorResponse(
            OllamaNode node,
            InferenceRequest request,
            HttpResponse<String> response,
            Duration totalDuration
    ) {
        ErrorType errorType;
        int statusCode = response.statusCode();

        if (statusCode >= 400 && statusCode < 500) {
            errorType = ErrorType.CLIENT_ERROR;
        } else if (statusCode >= 500) {
            errorType = ErrorType.NODE_ERROR;
        } else {
            errorType = ErrorType.INTERNAL_ERROR;
        }

        String errorMessage = "HTTP " + statusCode;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> errorBody = objectMapper.readValue(response.body(), Map.class);
            if (errorBody.containsKey("error")) {
                errorMessage = (String) errorBody.get("error");
            }
        } catch (Exception ignored) {
            // Use default error message
        }

        return InferenceResponse.builder()
                .requestId(request.requestId())
                .model(request.model())
                .done(false)
                .nodeId(node.getId())
                .createdAt(request.createdAt())
                .completedAt(Instant.now())
                .totalDuration(totalDuration)
                .errorType(errorType)
                .errorMessage(errorMessage)
                .build();
    }

    private InferenceResponse handleException(
            OllamaNode node,
            InferenceRequest request,
            Throwable ex,
            CircuitBreaker breaker
    ) {
        breaker.recordFailure();

        ErrorType errorType = classifyException(ex);
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        String message = cause.getMessage();

        if (errorType == ErrorType.TIMEOUT) {
            log.error("Request timeout: nodeId={}, requestId={}, model={}, error={}",
                    node.getId(), request.requestId(), request.model(), message);
        } else if (errorType == ErrorType.NODE_ERROR) {
            log.error("Node connection error: nodeId={}, requestId={}, model={}, errorType={}, error={}",
                    node.getId(), request.requestId(), request.model(), cause.getClass().getSimpleName(), message);
        } else {
            log.error("Request failed unexpectedly: nodeId={}, requestId={}, model={}, errorType={}, error={}",
                    node.getId(), request.requestId(), request.model(), cause.getClass().getSimpleName(), message, ex);
        }

        return InferenceResponse.error(
                request.requestId(),
                request.model(),
                errorType,
                message,
                request.createdAt()
        );
    }

    private ErrorType classifyException(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof java.util.concurrent.TimeoutException) {
            return ErrorType.TIMEOUT;
        }
        if (cause instanceof java.net.ConnectException
                || cause instanceof java.net.http.HttpConnectTimeoutException) {
            return ErrorType.NODE_ERROR;
        }
        if (cause instanceof IOException) {
            return ErrorType.NODE_ERROR;
        }
        return ErrorType.INTERNAL_ERROR;
    }

    private CircuitBreaker getOrCreateCircuitBreaker(String nodeId) {
        return circuitBreakers.computeIfAbsent(nodeId, id ->
                new CircuitBreaker(id, failureThreshold, circuitBreakerRecoveryTimeout, 3)
        );
    }

    /**
     * Gets the circuit breaker for a specific node.
     */
    public CircuitBreaker getCircuitBreaker(String nodeId) {
        return circuitBreakers.get(nodeId);
    }

    /**
     * Resets the circuit breaker for a node.
     */
    public void resetCircuitBreaker(String nodeId) {
        CircuitBreaker breaker = circuitBreakers.get(nodeId);
        if (breaker != null) {
            breaker.forceState(CircuitBreaker.State.CLOSED);
        }
    }

    /**
     * Performs a health check against a node.
     */
    public CompletableFuture<Boolean> healthCheck(OllamaNode node) {
        URI uri = URI.create(node.getBaseUrl().toString().replaceAll("/$", "") + "/api/tags");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        log.debug("Health check started: nodeId={}, uri={}", node.getId(), uri);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    boolean healthy = response.statusCode() == 200;
                    if (healthy) {
                        log.debug("Health check passed: nodeId={}, status={}", node.getId(), response.statusCode());
                    } else {
                        log.warn("Health check failed: nodeId={}, status={}", node.getId(), response.statusCode());
                    }
                    return healthy;
                })
                .exceptionally(ex -> {
                    log.warn("Health check error: nodeId={}, error={}", node.getId(), ex.getMessage());
                    return false;
                });
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit closing in Java 17+
    }
}
