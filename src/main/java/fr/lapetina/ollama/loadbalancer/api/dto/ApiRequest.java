package fr.lapetina.ollama.loadbalancer.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;

import java.util.List;
import java.util.Map;

/**
 * API request DTO compatible with Ollama API format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiRequest {

    private String model;
    private String prompt;
    private List<Message> messages;
    private Map<String, Object> options;
    private boolean stream;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("correlation_id")
    private String correlationId;

    // Getters and setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }

    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    /**
     * Converts to domain InferenceRequest.
     */
    public InferenceRequest toInferenceRequest() {
        List<InferenceRequest.Message> domainMessages = null;
        if (messages != null) {
            domainMessages = messages.stream()
                    .map(m -> new InferenceRequest.Message(m.getRole(), m.getContent()))
                    .toList();
        }

        return InferenceRequest.builder()
                .requestId(requestId)
                .model(model)
                .prompt(prompt)
                .messages(domainMessages)
                .options(options)
                .stream(stream)
                .correlationId(correlationId)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
