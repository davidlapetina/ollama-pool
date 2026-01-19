package fr.lapetina.ollama.loadbalancer.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;

import java.time.Instant;

/**
 * API response DTO compatible with Ollama API format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {

    private String model;
    private String response;
    private boolean done;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("total_duration")
    private Long totalDurationNs;

    @JsonProperty("load_duration")
    private Long loadDurationNs;

    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;

    @JsonProperty("prompt_eval_duration")
    private Long promptEvalDurationNs;

    @JsonProperty("eval_count")
    private Integer evalCount;

    @JsonProperty("eval_duration")
    private Long evalDurationNs;

    // Load balancer specific fields
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("node_id")
    private String nodeId;

    private String error;

    @JsonProperty("error_type")
    private String errorType;

    // Getters and setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getTotalDurationNs() { return totalDurationNs; }
    public void setTotalDurationNs(Long totalDurationNs) { this.totalDurationNs = totalDurationNs; }

    public Long getLoadDurationNs() { return loadDurationNs; }
    public void setLoadDurationNs(Long loadDurationNs) { this.loadDurationNs = loadDurationNs; }

    public Integer getPromptEvalCount() { return promptEvalCount; }
    public void setPromptEvalCount(Integer promptEvalCount) { this.promptEvalCount = promptEvalCount; }

    public Long getPromptEvalDurationNs() { return promptEvalDurationNs; }
    public void setPromptEvalDurationNs(Long promptEvalDurationNs) { this.promptEvalDurationNs = promptEvalDurationNs; }

    public Integer getEvalCount() { return evalCount; }
    public void setEvalCount(Integer evalCount) { this.evalCount = evalCount; }

    public Long getEvalDurationNs() { return evalDurationNs; }
    public void setEvalDurationNs(Long evalDurationNs) { this.evalDurationNs = evalDurationNs; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    /**
     * Creates from domain InferenceResponse.
     */
    public static ApiResponse fromInferenceResponse(InferenceResponse response) {
        ApiResponse api = new ApiResponse();
        api.setRequestId(response.requestId());
        api.setModel(response.model());
        api.setResponse(response.response());
        api.setDone(response.done());
        api.setNodeId(response.nodeId());
        api.setCreatedAt(response.createdAt());

        if (response.totalDuration() != null) {
            api.setTotalDurationNs(response.totalDuration().toNanos());
        }
        if (response.loadDuration() != null) {
            api.setLoadDurationNs(response.loadDuration().toNanos());
        }
        if (response.promptEvalDuration() != null) {
            api.setPromptEvalDurationNs(response.promptEvalDuration().toNanos());
        }
        if (response.evalDuration() != null) {
            api.setEvalDurationNs(response.evalDuration().toNanos());
        }
        api.setPromptEvalCount(response.promptEvalCount());
        api.setEvalCount(response.evalCount());

        if (response.errorType() != null) {
            api.setErrorType(response.errorType().name());
            api.setError(response.errorMessage());
        }

        return api;
    }

    /**
     * Creates an error response.
     */
    public static ApiResponse error(String requestId, String errorType, String message) {
        ApiResponse api = new ApiResponse();
        api.setRequestId(requestId);
        api.setDone(false);
        api.setErrorType(errorType);
        api.setError(message);
        return api;
    }
}
