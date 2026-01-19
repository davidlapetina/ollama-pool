package fr.lapetina.ollama.loadbalancer.infrastructure.health;

import fr.lapetina.ollama.loadbalancer.domain.model.NodeHealth;
import fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registry for managing Ollama nodes.
 *
 * Thread-safe storage and access for the node pool.
 * Supports dynamic updates and notifications.
 */
public final class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final Map<String, OllamaNode> nodes = new ConcurrentHashMap<>();
    private final List<Consumer<NodeRegistryEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Registers a new node or updates an existing one.
     */
    public void registerNode(OllamaNode node) {
        OllamaNode previous = nodes.put(node.getId(), node);
        if (previous == null) {
            log.info("Node registered: {}", node);
            notifyListeners(new NodeRegistryEvent(NodeRegistryEvent.Type.ADDED, node));
        } else {
            log.info("Node updated: {}", node);
            notifyListeners(new NodeRegistryEvent(NodeRegistryEvent.Type.UPDATED, node));
        }
    }

    /**
     * Removes a node by ID.
     */
    public OllamaNode removeNode(String nodeId) {
        OllamaNode removed = nodes.remove(nodeId);
        if (removed != null) {
            log.info("Node removed: {}", removed);
            notifyListeners(new NodeRegistryEvent(NodeRegistryEvent.Type.REMOVED, removed));
        }
        return removed;
    }

    /**
     * Gets a node by ID.
     */
    public Optional<OllamaNode> getNode(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    /**
     * Gets all registered nodes.
     */
    public List<OllamaNode> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * Gets all nodes that are enabled (regardless of health).
     */
    public List<OllamaNode> getEnabledNodes() {
        return nodes.values().stream()
                .filter(OllamaNode::isEnabled)
                .toList();
    }

    /**
     * Gets all active nodes (enabled and not DOWN).
     */
    public List<OllamaNode> getActiveNodes() {
        return nodes.values().stream()
                .filter(node -> node.isEnabled() && node.getHealth() != NodeHealth.DOWN)
                .toList();
    }

    /**
     * Gets all healthy nodes (UP status only).
     */
    public List<OllamaNode> getHealthyNodes() {
        return nodes.values().stream()
                .filter(node -> node.isEnabled() && node.getHealth() == NodeHealth.UP)
                .toList();
    }

    /**
     * Gets nodes that can handle a specific model.
     */
    public List<OllamaNode> getNodesForModel(String model) {
        return nodes.values().stream()
                .filter(node -> node.canHandle(model))
                .toList();
    }

    /**
     * Updates the health status of a node.
     */
    public void updateNodeHealth(String nodeId, NodeHealth health) {
        OllamaNode node = nodes.get(nodeId);
        if (node != null) {
            NodeHealth previous = node.getHealth();
            node.setHealth(health);
            if (previous != health) {
                log.info("Node health changed: nodeId={}, {} -> {}", nodeId, previous, health);
                notifyListeners(new NodeRegistryEvent(NodeRegistryEvent.Type.HEALTH_CHANGED, node));
            }
        }
    }

    /**
     * Replaces all nodes with a new set.
     * Used for configuration reload.
     */
    public void replaceAll(Collection<OllamaNode> newNodes) {
        Set<String> newNodeIds = new HashSet<>();

        for (OllamaNode node : newNodes) {
            newNodeIds.add(node.getId());
            registerNode(node);
        }

        // Remove nodes that are no longer in the config
        for (String existingId : new ArrayList<>(nodes.keySet())) {
            if (!newNodeIds.contains(existingId)) {
                removeNode(existingId);
            }
        }

        log.info("Node registry replaced: {} nodes active", nodes.size());
    }

    /**
     * Adds a listener for registry events.
     */
    public void addListener(Consumer<NodeRegistryEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Removes a listener.
     */
    public void removeListener(Consumer<NodeRegistryEvent> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(NodeRegistryEvent event) {
        for (Consumer<NodeRegistryEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error notifying listener", e);
            }
        }
    }

    /**
     * Returns the number of registered nodes.
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Clears all nodes.
     */
    public void clear() {
        for (String nodeId : new ArrayList<>(nodes.keySet())) {
            removeNode(nodeId);
        }
    }

    /**
     * Event for node registry changes.
     */
    public record NodeRegistryEvent(Type type, OllamaNode node) {
        public enum Type {
            ADDED,
            REMOVED,
            UPDATED,
            HEALTH_CHANGED
        }
    }
}
