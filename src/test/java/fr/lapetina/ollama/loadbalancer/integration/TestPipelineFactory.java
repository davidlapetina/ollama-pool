package fr.lapetina.ollama.loadbalancer.integration;

import fr.lapetina.ollama.loadbalancer.PipelineFactory;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import fr.lapetina.ollama.loadbalancer.infrastructure.http.OllamaHttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Test extension of PipelineFactory that provides stub HTTP client functionality.
 */
public final class TestPipelineFactory extends PipelineFactory {

    private final StubHttpClient stubHttpClient;

    private TestPipelineFactory(String configPath) {
        super(configPath, new StubHttpClient());
        this.stubHttpClient = (StubHttpClient) getHttpClient();
    }

    /**
     * Creates a test factory from the default test configuration.
     */
    public static TestPipelineFactory create() {
        return create("test-config.yaml");
    }

    /**
     * Creates a test factory from a custom configuration path.
     */
    public static TestPipelineFactory create(String configPath) {
        TestPipelineFactory factory = new TestPipelineFactory(configPath);
        factory.start();
        return factory;
    }

    /**
     * Sets the stub response generator for HTTP requests.
     */
    public void setHttpResponse(Function<InferenceRequest, InferenceResponse> responseGenerator) {
        stubHttpClient.setResponseGenerator(responseGenerator);
    }

    /**
     * Sets a fixed successful response for all requests.
     */
    public void setSuccessResponse(String responseText) {
        stubHttpClient.setResponseGenerator(req -> InferenceResponse.success(
                req.requestId(), req.model(), responseText, "node-1", Instant.now()
        ));
    }

    /**
     * Sets the stub to throw exceptions for all requests.
     */
    public void setErrorResponse(RuntimeException exception) {
        stubHttpClient.setException(exception);
    }

    /**
     * Stub HTTP client for testing.
     */
    static class StubHttpClient extends OllamaHttpClient {
        private Function<InferenceRequest, InferenceResponse> responseGenerator;
        private RuntimeException exception;

        StubHttpClient() {
            super(Duration.ofSeconds(10), 5, Duration.ofSeconds(30));
        }

        void setResponseGenerator(Function<InferenceRequest, InferenceResponse> generator) {
            this.responseGenerator = generator;
            this.exception = null;
        }

        void setException(RuntimeException exception) {
            this.exception = exception;
            this.responseGenerator = null;
        }

        @Override
        public CompletableFuture<InferenceResponse> sendRequest(OllamaNode node, InferenceRequest request) {
            if (exception != null) {
                return CompletableFuture.failedFuture(exception);
            }

            if (responseGenerator == null) {
                return CompletableFuture.completedFuture(
                        InferenceResponse.success(
                                request.requestId(),
                                request.model(),
                                "Default test response",
                                node.getId(),
                                Instant.now()
                        )
                );
            }

            try {
                return CompletableFuture.completedFuture(responseGenerator.apply(request));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }
}
