package fr.lapetina.ollama.loadbalancer.disruptor.handlers;

import fr.lapetina.ollama.loadbalancer.domain.event.EventState;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationHandlerTest {

    private ValidationHandler handler;
    private InferenceRequestEvent event;

    @BeforeEach
    void setUp() {
        handler = new ValidationHandler(Set.of(), 10000);
        event = new InferenceRequestEvent();
    }

    @Test
    @DisplayName("should validate valid prompt request")
    void shouldValidateValidPromptRequest() {
        InferenceRequest request = InferenceRequest.ofPrompt("llama2", "Hello, world!");
        event.initialize(request, new CompletableFuture<>(), 3);

        handler.onEvent(event, 0, true);

        assertThat(event.getState()).isEqualTo(EventState.VALIDATED);
        assertThat(event.getErrorType()).isNull();
    }

    @Test
    @DisplayName("should validate valid chat request")
    void shouldValidateValidChatRequest() {
        InferenceRequest request = InferenceRequest.ofChat("llama2", List.of(
                new InferenceRequest.Message("user", "Hello!"),
                new InferenceRequest.Message("assistant", "Hi there!")
        ));
        event.initialize(request, new CompletableFuture<>(), 3);

        handler.onEvent(event, 0, true);

        assertThat(event.getState()).isEqualTo(EventState.VALIDATED);
    }

    @Test
    @DisplayName("should reject request with missing model")
    void shouldRejectMissingModel() {
        // Can't create request without model due to validation in constructor
        // So test with null request
        event.initialize(null, new CompletableFuture<>(), 3);
        // Manually set a bad state to test null handling
        event.setState(EventState.CREATED);

        handler.onEvent(event, 0, true);

        assertThat(event.getState()).isEqualTo(EventState.VALIDATION_FAILED);
        assertThat(event.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("should reject prompt exceeding max length")
    void shouldRejectLongPrompt() {
        // Create handler with small max length
        handler = new ValidationHandler(Set.of(), 10);

        String longPrompt = "A".repeat(100);
        InferenceRequest request = InferenceRequest.ofPrompt("llama2", longPrompt);
        event.initialize(request, new CompletableFuture<>(), 3);

        handler.onEvent(event, 0, true);

        assertThat(event.getState()).isEqualTo(EventState.VALIDATION_FAILED);
        assertThat(event.getErrorMessage()).contains("maximum length");
    }

    @Test
    @DisplayName("should reject model not in allowed list")
    void shouldRejectDisallowedModel() {
        handler = new ValidationHandler(Set.of("llama2", "codellama"), 10000);

        InferenceRequest request = InferenceRequest.ofPrompt("mistral", "Hello");
        event.initialize(request, new CompletableFuture<>(), 3);

        handler.onEvent(event, 0, true);

        assertThat(event.getState()).isEqualTo(EventState.VALIDATION_FAILED);
        assertThat(event.getErrorMessage()).contains("not allowed");
    }

    @Test
    @DisplayName("should allow model in allowed list")
    void shouldAllowAllowedModel() {
        handler = new ValidationHandler(Set.of("llama2", "codellama"), 10000);

        InferenceRequest request = InferenceRequest.ofPrompt("llama2", "Hello");
        event.initialize(request, new CompletableFuture<>(), 3);

        handler.onEvent(event, 0, true);

        assertThat(event.getState()).isEqualTo(EventState.VALIDATED);
    }

    @Test
    @DisplayName("should skip already failed events")
    void shouldSkipFailedEvents() {
        InferenceRequest request = InferenceRequest.ofPrompt("llama2", "Hello");
        event.initialize(request, new CompletableFuture<>(), 3);
        event.setState(EventState.FAILED);

        handler.onEvent(event, 0, true);

        // State should remain FAILED, not changed to VALIDATED
        assertThat(event.getState()).isEqualTo(EventState.FAILED);
    }
}
