package fr.lapetina.ollama.loadbalancer.disruptor.handlers;

import com.lmax.disruptor.EventHandler;
import fr.lapetina.ollama.loadbalancer.domain.event.EventState;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * First stage handler: validates incoming inference requests.
 *
 * Validates:
 * - Request is not null
 * - Model name is valid (if model whitelist configured)
 * - Prompt or messages are present
 * - Request size is within limits
 */
public final class ValidationHandler implements EventHandler<InferenceRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(ValidationHandler.class);

    private final Set<String> allowedModels;
    private final int maxPromptLength;

    public ValidationHandler(Set<String> allowedModels, int maxPromptLength) {
        this.allowedModels = allowedModels;
        this.maxPromptLength = maxPromptLength;
    }

    /**
     * Creates a handler with no model restrictions and default prompt length.
     */
    public static ValidationHandler withDefaults() {
        return new ValidationHandler(Set.of(), 100_000);
    }

    @Override
    public void onEvent(InferenceRequestEvent event, long sequence, boolean endOfBatch) {
        if (event.shouldSkip()) {
            log.debug("Skipping already processed event: sequence={}", sequence);
            return;
        }

        event.setSequence(sequence);
        InferenceRequest request = event.getRequest();

        log.debug("Validation started: sequence={}, requestId={}",
                sequence, request != null ? request.requestId() : "null");

        try {
            validate(event);
            event.markValidated();

            log.info("Request validated: requestId={}, model={}, sequence={}",
                    request.requestId(), request.model(), sequence);

        } catch (ValidationException e) {
            event.setState(EventState.VALIDATION_FAILED);
            event.setError(ErrorType.VALIDATION_ERROR, e.getMessage());

            log.warn("Validation failed: requestId={}, model={}, reason={}, sequence={}",
                    request != null ? request.requestId() : "null",
                    request != null ? request.model() : "null",
                    e.getMessage(),
                    sequence);
        }
    }

    private void validate(InferenceRequestEvent event) throws ValidationException {
        InferenceRequest request = event.getRequest();

        if (request == null) {
            throw new ValidationException("Request is null");
        }

        String model = request.model();
        if (model == null || model.isBlank()) {
            throw new ValidationException("Model name is required");
        }

        // Check model whitelist if configured
        if (!allowedModels.isEmpty() && !allowedModels.contains(model)) {
            throw new ValidationException("Model not allowed: " + model);
        }

        // Validate prompt/messages
        String prompt = request.prompt();
        if (prompt == null || prompt.isBlank()) {
            if (request.messages() == null || request.messages().isEmpty()) {
                throw new ValidationException("Either prompt or messages must be provided");
            }
        }

        // Check prompt length
        if (prompt != null && prompt.length() > maxPromptLength) {
            throw new ValidationException("Prompt exceeds maximum length of " + maxPromptLength);
        }

        // Validate messages if present
        if (request.messages() != null) {
            for (var message : request.messages()) {
                if (message.role() == null || message.role().isBlank()) {
                    throw new ValidationException("Message role is required");
                }
                if (message.content() == null) {
                    throw new ValidationException("Message content is required");
                }
                if (message.content().length() > maxPromptLength) {
                    throw new ValidationException("Message content exceeds maximum length");
                }
            }
        }
    }

    private static class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }
    }
}
