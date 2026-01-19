package fr.lapetina.ollama.loadbalancer.integration;

import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Disruptor pipeline.
 * Configuration is externalized to test-config.yaml.
 */
class DisruptorPipelineIntegrationTest {

    private TestPipelineFactory factory;

    @BeforeEach
    void setUp() {
        factory = TestPipelineFactory.create();
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("should process valid request through pipeline")
    void shouldProcessValidRequest() throws Exception {
        factory.setSuccessResponse("Hello, world!");

        InferenceRequest request = InferenceRequest.ofPrompt("llama2", "Say hello");
        CompletableFuture<InferenceResponse> future = factory.getPipeline().submit(request);

        InferenceResponse response = future.get(5, TimeUnit.SECONDS);

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.response()).isEqualTo("Hello, world!");
    }

    @Test
    @DisplayName("should reject request for unknown model")
    void shouldRejectRequestForUnknownModel() throws Exception {
        InferenceRequest request = InferenceRequest.ofPrompt("unknown-model", "Hello");
        CompletableFuture<InferenceResponse> future = factory.getPipeline().submit(request);

        InferenceResponse response = future.get(5, TimeUnit.SECONDS);

        assertThat(response.isError()).isTrue();
        assertThat(response.errorType()).isEqualTo(ErrorType.NO_AVAILABLE_NODE);
    }

    @Test
    @DisplayName("should handle HTTP client failure")
    void shouldHandleHttpClientFailure() throws Exception {
        factory.setErrorResponse(new RuntimeException("Connection refused"));

        InferenceRequest request = InferenceRequest.ofPrompt("llama2", "Hello");
        CompletableFuture<InferenceResponse> future = factory.getPipeline().submit(request);

        InferenceResponse response = future.get(5, TimeUnit.SECONDS);

        assertThat(response.isError()).isTrue();
    }

    @Test
    @DisplayName("should process multiple concurrent requests")
    void shouldProcessMultipleConcurrentRequests() throws Exception {
        factory.setHttpResponse(req -> InferenceResponse.success(
                req.requestId(), req.model(),
                "Response for " + req.requestId(),
                "node-1", Instant.now()
        ));

        int count = 5;
        CompletableFuture<InferenceResponse>[] futures = new CompletableFuture[count];
        for (int i = 0; i < count; i++) {
            futures[i] = factory.getPipeline().submit(
                    InferenceRequest.ofPrompt("llama2", "Hello " + i)
            );
        }

        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

        int successCount = 0;
        for (CompletableFuture<InferenceResponse> future : futures) {
            if (future.get().isSuccess()) {
                successCount++;
            }
        }

        assertThat(successCount).isGreaterThan(0);
    }
}
