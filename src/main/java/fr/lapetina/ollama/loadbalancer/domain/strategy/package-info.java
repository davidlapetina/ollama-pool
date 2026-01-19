/**
 * Load balancing strategies for distributing requests across Ollama nodes.
 *
 * <p>This package provides pluggable strategy implementations following the Strategy pattern.
 * All implementations are thread-safe for concurrent access from Disruptor handlers.
 *
 * <h2>Available Strategies</h2>
 * <table border="1">
 *   <tr><th>Strategy</th><th>Description</th><th>Best For</th></tr>
 *   <tr><td>{@code round-robin}</td><td>Cycles through nodes in order</td><td>Homogeneous nodes</td></tr>
 *   <tr><td>{@code weighted-round-robin}</td><td>Respects node weights</td><td>Heterogeneous capacities</td></tr>
 *   <tr><td>{@code least-in-flight}</td><td>Selects least loaded node</td><td>Variable response times</td></tr>
 *   <tr><td>{@code random}</td><td>Random selection</td><td>Simple, low overhead</td></tr>
 *   <tr><td>{@code model-aware}</td><td>Routes by model availability</td><td>Multi-model deployments</td></tr>
 * </table>
 *
 * <h2>Custom Strategies</h2>
 * <p>Implement {@link fr.lapetina.ollama.loadbalancer.domain.strategy.LoadBalancingStrategy} and register
 * with {@link fr.lapetina.ollama.loadbalancer.domain.strategy.StrategyFactory}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * LoadBalancingStrategy strategy = StrategyFactory.create("least-in-flight").orElseThrow();
 * Optional<OllamaNode> node = strategy.selectNode(nodes, "llama2");
 * }</pre>
 *
 * @see fr.lapetina.ollama.loadbalancer.domain.strategy.LoadBalancingStrategy
 * @see fr.lapetina.ollama.loadbalancer.domain.strategy.StrategyFactory
 */
package fr.lapetina.ollama.loadbalancer.domain.strategy;
