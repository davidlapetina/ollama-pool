package fr.lapetina.ollama.loadbalancer.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an inference request to be dispatched to an Ollama server.
 * Immutable and thread-safe.
 */
public record InferenceRequest(
        String requestId,
        String model,
        String prompt,
        List<Message> messages,
        List<String> images,
        Map<String, Object> options,
        boolean stream,
        Instant createdAt,
        String correlationId
) {
    public InferenceRequest {
        Objects.requireNonNull(model, "Model is required");
        if (prompt == null && (messages == null || messages.isEmpty())) {
            throw new IllegalArgumentException("Either prompt or messages must be provided");
        }
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (correlationId == null) {
            correlationId = requestId;
        }
        messages = messages != null ? List.copyOf(messages) : List.of();
        images = images != null ? List.copyOf(images) : List.of();
        options = options != null ? Map.copyOf(options) : Map.of();
    }

    /**
     * Chat message for conversation-style requests.
     */
    public record Message(String role, String content) {
        public Message {
            Objects.requireNonNull(role, "Role is required");
            Objects.requireNonNull(content, "Content is required");
        }
    }

    /**
     * Creates a simple prompt-based request.
     */
    public static InferenceRequest ofPrompt(String model, String prompt) {
        return new InferenceRequest(
                null, model, prompt, null, null, null, false, null, null
        );
    }

    /**
     * Creates a vision request with prompt and images.
     *
     * @param model  the vision model name
     * @param prompt the text prompt
     * @param images list of base64-encoded images
     */
    public static InferenceRequest ofVision(String model, String prompt, List<String> images) {
        return new InferenceRequest(
                null, model, prompt, null, images, null, false, null, null
        );
    }

    /**
     * Creates a chat-style request with messages.
     */
    public static InferenceRequest ofChat(String model, List<Message> messages) {
        return new InferenceRequest(
                null, model, null, messages, null, null, false, null, null
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String requestId;
        private String model;
        private String prompt;
        private List<Message> messages;
        private List<String> images;
        private Map<String, Object> options;
        private boolean stream;
        private Instant createdAt;
        private String correlationId;

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder images(List<String> images) {
            this.images = images;
            return this;
        }

        public Builder options(Map<String, Object> options) {
            this.options = options;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public InferenceRequest build() {
            return new InferenceRequest(
                    requestId, model, prompt, messages, images, options,
                    stream, createdAt, correlationId
            );
        }
    }
}
