package fr.lapetina.ollama.loadbalancer.disruptor.handlers;

import com.lmax.disruptor.EventHandler;
import fr.lapetina.ollama.loadbalancer.domain.event.EventState;
import fr.lapetina.ollama.loadbalancer.domain.event.InferenceRequestEvent;
import fr.lapetina.ollama.loadbalancer.domain.model.ErrorType;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import fr.lapetina.ollama.loadbalancer.domain.strategy.LoadBalancingStrategy;
import fr.lapetina.ollama.loadbalancer.infrastructure.health.NodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Second stage handler: selects a node using the configured load balancing strategy.
 *
 * Node selection considers:
 * - Model availability on nodes
 * - Node health status
 * - Current capacity/in-flight requests
 * - Load balancing strategy
 */
public final class NodeSelectionHandler implements EventHandler<InferenceRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(NodeSelectionHandler.class);

    private final NodeRegistry nodeRegistry;
    private final AtomicReference<LoadBalancingStrategy> strategyRef;

    public NodeSelectionHandler(
            NodeRegistry nodeRegistry,
            LoadBalancingStrategy initialStrategy
    ) {
        this.nodeRegistry = nodeRegistry;
        this.strategyRef = new AtomicReference<>(initialStrategy);
    }

    /**
     * Atomically updates the load balancing strategy.
     * Safe to call while the handler is processing events.
     */
    public void setStrategy(LoadBalancingStrategy strategy) {
        LoadBalancingStrategy old = strategyRef.getAndSet(strategy);
        log.info("Strategy changed from {} to {}", old.getName(), strategy.getName());
    }

    public LoadBalancingStrategy getStrategy() {
        return strategyRef.get();
    }

    @Override
    public void onEvent(InferenceRequestEvent event, long sequence, boolean endOfBatch) {
        if (event.shouldSkip()) {
            return;
        }

        if (event.getState() != EventState.VALIDATED) {
            log.debug("Skipping node selection - event not validated: sequence={}, state={}",
                    sequence, event.getState());
            return;
        }

        String model = event.getRequest().model();
        String requestId = event.getRequest().requestId();
        LoadBalancingStrategy strategy = strategyRef.get();

        var activeNodes = nodeRegistry.getActiveNodes();
        log.debug("Selecting node: requestId={}, model={}, strategy={}, availableNodes={}",
                requestId, model, strategy.getName(), activeNodes.size());

        Optional<OllamaNode> selected = strategy.selectNode(activeNodes, model);

        if (selected.isEmpty()) {
            handleNoNodeAvailable(event, model);
            return;
        }

        OllamaNode node = selected.get();
        event.setSelectedNode(node);
        event.setState(EventState.NODE_SELECTED);

        log.info("Node selected: requestId={}, nodeId={}, model={}, strategy={}, nodeInFlight={}/{}",
                requestId,
                node.getId(),
                model,
                strategy.getName(),
                node.getInFlightRequests(),
                node.getMaxConcurrentRequests());
    }

    private void handleNoNodeAvailable(InferenceRequestEvent event, String model) {
        // Determine the specific reason
        boolean anyNodesExist = !nodeRegistry.getActiveNodes().isEmpty();
        boolean anyNodeHasModel = nodeRegistry.getActiveNodes().stream()
                .anyMatch(n -> n.hasModel(model));

        String reason;
        if (!anyNodesExist) {
            reason = "No active nodes in the pool";
        } else if (!anyNodeHasModel) {
            reason = "No nodes host model: " + model;
        } else {
            reason = "All nodes with model '" + model + "' are at capacity";
        }

        event.setState(EventState.NO_NODE_AVAILABLE);
        event.setError(ErrorType.NO_AVAILABLE_NODE, reason);

        log.warn("No node available: requestId={}, model={}, reason={}",
                event.getRequest().requestId(),
                model,
                reason);
    }
}
