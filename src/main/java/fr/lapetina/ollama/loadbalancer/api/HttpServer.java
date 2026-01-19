package fr.lapetina.ollama.loadbalancer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.lapetina.ollama.loadbalancer.api.dto.ApiRequest;
import fr.lapetina.ollama.loadbalancer.api.dto.ApiResponse;
import fr.lapetina.ollama.loadbalancer.disruptor.DisruptorPipeline;
import fr.lapetina.ollama.loadbalancer.disruptor.exception.BackpressureException;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import fr.lapetina.ollama.loadbalancer.domain.model.NodeHealth;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import fr.lapetina.ollama.loadbalancer.domain.strategy.LoadBalancingStrategy;
import fr.lapetina.ollama.loadbalancer.domain.strategy.StrategyFactory;
import fr.lapetina.ollama.loadbalancer.infrastructure.config.ConfigLoader;
import fr.lapetina.ollama.loadbalancer.infrastructure.config.LoadBalancerConfig;
import fr.lapetina.ollama.loadbalancer.infrastructure.health.NodeRegistry;
import fr.lapetina.ollama.loadbalancer.infrastructure.metrics.MetricsRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight HTTP server using JDK's built-in HttpServer.
 *
 * Endpoints:
 * - POST /v1/inference - Submit inference request (Ollama compatible)
 * - POST /api/generate - Alias for inference (Ollama compatible)
 * - POST /api/chat - Chat-style inference (Ollama compatible)
 * - GET /health - Health check endpoint
 * - GET /metrics - Prometheus metrics endpoint
 * - GET /admin/nodes - List all nodes
 * - POST /admin/nodes/{id}/enable - Enable a node
 * - POST /admin/nodes/{id}/disable - Disable a node
 * - POST /admin/strategy - Change load balancing strategy
 * - POST /admin/reload - Reload configuration
 */
public final class HttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final com.sun.net.httpserver.HttpServer server;
    private final ObjectMapper objectMapper;
    private final DisruptorPipeline pipeline;
    private final NodeRegistry nodeRegistry;
    private final MetricsRegistry metricsRegistry;
    private final ConfigLoader configLoader;

    public HttpServer(
            int port,
            int backlog,
            DisruptorPipeline pipeline,
            NodeRegistry nodeRegistry,
            MetricsRegistry metricsRegistry,
            ConfigLoader configLoader
    ) throws IOException {
        this.pipeline = pipeline;
        this.nodeRegistry = nodeRegistry;
        this.metricsRegistry = metricsRegistry;
        this.configLoader = configLoader;

        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        this.server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(port), backlog
        );

        // Use virtual threads if available (Java 21+), otherwise thread pool
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Register handlers
        server.createContext("/v1/inference", new InferenceHandler());
        server.createContext("/api/generate", new InferenceHandler());
        server.createContext("/api/chat", new InferenceHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/metrics", new MetricsHandler());
        server.createContext("/admin", new AdminHandler());

        log.info("HTTP server configured on port {}", port);
    }

    public void start() {
        server.start();
        log.info("HTTP server started");
    }

    @Override
    public void close() {
        server.stop(5);
        log.info("HTTP server stopped");
    }

    // ==================== INFERENCE HANDLER ====================

    private class InferenceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestId = UUID.randomUUID().toString();
            MDC.put("requestId", requestId);

            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }

                // Parse request
                ApiRequest apiRequest;
                try (InputStream is = exchange.getRequestBody()) {
                    apiRequest = objectMapper.readValue(is, ApiRequest.class);
                }

                if (apiRequest.getRequestId() == null) {
                    apiRequest.setRequestId(requestId);
                }

                // Extract correlation ID from header if present
                String correlationId = exchange.getRequestHeaders()
                        .getFirst("X-Correlation-ID");
                if (correlationId != null && apiRequest.getCorrelationId() == null) {
                    apiRequest.setCorrelationId(correlationId);
                }

                InferenceRequest request = apiRequest.toInferenceRequest();

                // Submit to pipeline
                CompletableFuture<InferenceResponse> future;
                try {
                    future = pipeline.submit(request);
                } catch (BackpressureException e) {
                    log.warn("Backpressure: {}", e.getMessage());
                    sendError(exchange, 503, e.getMessage());
                    return;
                }

                // Wait for response (with timeout)
                InferenceResponse response = future.get(120, TimeUnit.SECONDS);

                // Convert and send response
                ApiResponse apiResponse = ApiResponse.fromInferenceResponse(response);
                int statusCode = response.isSuccess() ? 200 : mapErrorToStatus(response);
                sendJson(exchange, statusCode, apiResponse);

            } catch (Exception e) {
                log.error("Error handling inference request", e);
                sendError(exchange, 500, "Internal server error: " + e.getMessage());
            } finally {
                MDC.clear();
            }
        }

        private int mapErrorToStatus(InferenceResponse response) {
            if (response.errorType() == null) return 500;
            return switch (response.errorType()) {
                case CLIENT_ERROR, VALIDATION_ERROR -> 400;
                case NO_AVAILABLE_NODE, CAPACITY_ERROR -> 503;
                case TIMEOUT -> 504;
                case CIRCUIT_OPEN -> 503;
                default -> 500;
            };
        }
    }

    // ==================== HEALTH HANDLER ====================

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", determineOverallHealth());
            health.put("timestamp", System.currentTimeMillis());

            // Node health
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (OllamaNode node : nodeRegistry.getAllNodes()) {
                Map<String, Object> nodeInfo = new LinkedHashMap<>();
                nodeInfo.put("id", node.getId());
                nodeInfo.put("health", node.getHealth().name());
                nodeInfo.put("enabled", node.isEnabled());
                nodeInfo.put("inFlight", node.getInFlightRequests());
                nodeInfo.put("maxConcurrent", node.getMaxConcurrentRequests());
                nodes.add(nodeInfo);
            }
            health.put("nodes", nodes);

            // Pipeline stats
            Map<String, Object> pipelineStats = new LinkedHashMap<>();
            pipelineStats.put("globalInFlight", pipeline.getGlobalInFlight());
            pipelineStats.put("ringBufferRemaining", pipeline.getRemainingCapacity());
            pipelineStats.put("strategy", pipeline.getStrategy().getName());
            health.put("pipeline", pipelineStats);

            int statusCode = "UP".equals(health.get("status")) ? 200 : 503;
            sendJson(exchange, statusCode, health);
        }

        private String determineOverallHealth() {
            List<OllamaNode> activeNodes = nodeRegistry.getActiveNodes();
            if (activeNodes.isEmpty()) {
                return "DOWN";
            }

            long healthyCount = activeNodes.stream()
                    .filter(n -> n.getHealth() == NodeHealth.UP)
                    .count();

            if (healthyCount == 0) {
                return "DOWN";
            } else if (healthyCount < activeNodes.size()) {
                return "DEGRADED";
            }
            return "UP";
        }
    }

    // ==================== METRICS HANDLER ====================

    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            // Update real-time gauges
            metricsRegistry.setGlobalInFlight(pipeline.getGlobalInFlight());
            metricsRegistry.setRingBufferRemaining((int) pipeline.getRemainingCapacity());
            metricsRegistry.setActiveNodes(nodeRegistry.getActiveNodes().size());

            String metrics = metricsRegistry.scrape();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            byte[] bytes = metrics.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ==================== ADMIN HANDLER ====================

    private class AdminHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            try {
                if (path.equals("/admin/nodes") && "GET".equals(method)) {
                    handleListNodes(exchange);
                } else if (path.matches("/admin/nodes/[^/]+/enable") && "POST".equals(method)) {
                    handleNodeAction(exchange, path, true);
                } else if (path.matches("/admin/nodes/[^/]+/disable") && "POST".equals(method)) {
                    handleNodeAction(exchange, path, false);
                } else if (path.equals("/admin/strategy") && "POST".equals(method)) {
                    handleChangeStrategy(exchange);
                } else if (path.equals("/admin/strategy") && "GET".equals(method)) {
                    handleGetStrategy(exchange);
                } else if (path.equals("/admin/reload") && "POST".equals(method)) {
                    handleReloadConfig(exchange);
                } else {
                    sendError(exchange, 404, "Not Found");
                }
            } catch (Exception e) {
                log.error("Error in admin handler", e);
                sendError(exchange, 500, e.getMessage());
            }
        }

        private void handleListNodes(HttpExchange exchange) throws IOException {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (OllamaNode node : nodeRegistry.getAllNodes()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", node.getId());
                info.put("url", node.getBaseUrl().toString());
                info.put("models", node.getAvailableModels());
                info.put("health", node.getHealth().name());
                info.put("enabled", node.isEnabled());
                info.put("weight", node.getWeight());
                info.put("maxConcurrent", node.getMaxConcurrentRequests());
                info.put("inFlight", node.getInFlightRequests());
                info.put("consecutiveFailures", node.getConsecutiveFailures());
                nodes.add(info);
            }
            sendJson(exchange, 200, nodes);
        }

        private void handleNodeAction(HttpExchange exchange, String path, boolean enable) throws IOException {
            // Extract node ID from path
            String[] parts = path.split("/");
            String nodeId = parts[3];

            Optional<OllamaNode> nodeOpt = nodeRegistry.getNode(nodeId);
            if (nodeOpt.isEmpty()) {
                sendError(exchange, 404, "Node not found: " + nodeId);
                return;
            }

            // Note: OllamaNode is immutable for enabled flag, so we'd need to
            // replace it in the registry. For simplicity, update health instead.
            if (!enable) {
                nodeRegistry.updateNodeHealth(nodeId, NodeHealth.DOWN);
            } else {
                nodeRegistry.updateNodeHealth(nodeId, NodeHealth.UP);
            }

            sendJson(exchange, 200, Map.of(
                    "node", nodeId,
                    "action", enable ? "enabled" : "disabled"
            ));
        }

        private void handleChangeStrategy(HttpExchange exchange) throws IOException {
            Map<String, String> request;
            try (InputStream is = exchange.getRequestBody()) {
                request = objectMapper.readValue(is, Map.class);
            }

            String strategyName = request.get("strategy");
            if (strategyName == null || strategyName.isBlank()) {
                sendError(exchange, 400, "Missing 'strategy' field");
                return;
            }

            Optional<LoadBalancingStrategy> strategy = StrategyFactory.create(strategyName);
            if (strategy.isEmpty()) {
                sendError(exchange, 400, "Unknown strategy: " + strategyName +
                        ". Available: " + StrategyFactory.getRegisteredNames());
                return;
            }

            pipeline.setStrategy(strategy.get());
            sendJson(exchange, 200, Map.of(
                    "strategy", strategyName,
                    "message", "Strategy changed successfully"
            ));
        }

        private void handleGetStrategy(HttpExchange exchange) throws IOException {
            sendJson(exchange, 200, Map.of(
                    "current", pipeline.getStrategy().getName(),
                    "available", StrategyFactory.getRegisteredNames()
            ));
        }

        private void handleReloadConfig(HttpExchange exchange) throws IOException {
            LoadBalancerConfig newConfig = configLoader.reload();
            sendJson(exchange, 200, Map.of(
                    "message", "Configuration reloaded",
                    "nodes", newConfig.getNodes().size()
            ));
        }
    }

    // ==================== HELPER METHODS ====================

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, String> error = Map.of("error", message);
        sendJson(exchange, statusCode, error);
    }
}
