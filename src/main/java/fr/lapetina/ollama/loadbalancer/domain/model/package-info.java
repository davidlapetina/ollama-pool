/**
 * Domain model classes representing core concepts in the load balancer.
 *
 * <p>This package contains immutable value objects and thread-safe entities
 * used throughout the load balancer.
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest} - Immutable request to be dispatched</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse} - Immutable response from an Ollama node</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode} - Thread-safe representation of an Ollama server</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.domain.model.NodeHealth} - Node health states (UP, DEGRADED, DOWN)</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.domain.model.ErrorType} - Categorized error types for responses</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All classes in this package are designed for concurrent access:
 * <ul>
 *   <li>{@code InferenceRequest} and {@code InferenceResponse} are immutable records</li>
 *   <li>{@code OllamaNode} uses {@code AtomicInteger} and {@code AtomicReference} for mutable state</li>
 * </ul>
 *
 * @see fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest
 * @see fr.lapetina.ollama.loadbalancer.domain.model.OllamaNode
 */
package fr.lapetina.ollama.loadbalancer.domain.model;
