package fr.lapetina.ollama.loadbalancer.domain.event;

import com.lmax.disruptor.EventFactory;

/**
 * Factory for creating InferenceRequestEvent instances in the Disruptor ring buffer.
 *
 * The Disruptor pre-allocates events at startup to avoid GC during operation.
 * Events are then reused by clearing and re-initializing them.
 */
public final class InferenceRequestEventFactory implements EventFactory<InferenceRequestEvent> {

    @Override
    public InferenceRequestEvent newInstance() {
        return new InferenceRequestEvent();
    }
}
