package fr.lapetina.ollama.loadbalancer.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a response from an Ollama inference request.
 * Immutable and thread-safe.
 */
public record InferenceResponse(
        String requestId,
        String model,
        String response,
        boolean done,
        String nodeId,
        Instant createdAt,
        Instant completedAt,
        Duration totalDuration,
        Duration loadDuration,
        Duration promptEvalDuration,
        Duration evalDuration,
        int promptEvalCount,
        int evalCount,
        Map<String, Object> context,
        ErrorType errorType,
        String errorMessage
) {
    public InferenceResponse {
        Objects.requireNonNull(requestId, "Request ID is required");
        if (completedAt == null) {
            completedAt = Instant.now();
        }
        context = context != null ? Map.copyOf(context) : Map.of();
    }

    public boolean isSuccess() {
        return errorType == null;
    }

    public boolean isError() {
        return errorType != null;
    }

    /**
     * Creates a successful response.
     */
    public static InferenceResponse success(
            String requestId,
            String model,
            String response,
            String nodeId,
            Instant createdAt
    ) {
        return new InferenceResponse(
                requestId, model, response, true, nodeId,
                createdAt, Instant.now(),
                Duration.between(createdAt, Instant.now()),
                null, null, null, 0, 0, null, null, null
        );
    }

    /**
     * Creates an error response.
     */
    public static InferenceResponse error(
            String requestId,
            String model,
            ErrorType errorType,
            String errorMessage,
            Instant createdAt
    ) {
        return new InferenceResponse(
                requestId, model, null, false, null,
                createdAt, Instant.now(),
                Duration.between(createdAt, Instant.now()),
                null, null, null, 0, 0, null, errorType, errorMessage
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String requestId;
        private String model;
        private String response;
        private boolean done;
        private String nodeId;
        private Instant createdAt;
        private Instant completedAt;
        private Duration totalDuration;
        private Duration loadDuration;
        private Duration promptEvalDuration;
        private Duration evalDuration;
        private int promptEvalCount;
        private int evalCount;
        private Map<String, Object> context;
        private ErrorType errorType;
        private String errorMessage;

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder response(String response) {
            this.response = response;
            return this;
        }

        public Builder done(boolean done) {
            this.done = done;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder totalDuration(Duration totalDuration) {
            this.totalDuration = totalDuration;
            return this;
        }

        public Builder loadDuration(Duration loadDuration) {
            this.loadDuration = loadDuration;
            return this;
        }

        public Builder promptEvalDuration(Duration promptEvalDuration) {
            this.promptEvalDuration = promptEvalDuration;
            return this;
        }

        public Builder evalDuration(Duration evalDuration) {
            this.evalDuration = evalDuration;
            return this;
        }

        public Builder promptEvalCount(int promptEvalCount) {
            this.promptEvalCount = promptEvalCount;
            return this;
        }

        public Builder evalCount(int evalCount) {
            this.evalCount = evalCount;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        public Builder errorType(ErrorType errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public InferenceResponse build() {
            return new InferenceResponse(
                    requestId, model, response, done, nodeId,
                    createdAt, completedAt, totalDuration, loadDuration,
                    promptEvalDuration, evalDuration, promptEvalCount, evalCount,
                    context, errorType, errorMessage
            );
        }
    }
}
