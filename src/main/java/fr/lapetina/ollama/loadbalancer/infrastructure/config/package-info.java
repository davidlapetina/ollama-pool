/**
 * Configuration loading and hot-reload support.
 *
 * <p>This package handles YAML configuration parsing and runtime configuration updates
 * without requiring application restart.
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.infrastructure.config.LoadBalancerConfig} - Configuration model</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.infrastructure.config.ConfigLoader} - YAML loading and file watching</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.infrastructure.config.ConfigChangeListener} - Callback for configuration changes</li>
 * </ul>
 *
 * <h2>Hot-Reload</h2>
 * <p>Configuration changes are detected via file system watching. When the configuration file
 * is modified, registered listeners are notified and can update their state accordingly.
 *
 * <h2>Configuration Sections</h2>
 * <ul>
 *   <li>{@code server} - HTTP server settings (port, backlog)</li>
 *   <li>{@code nodes} - Ollama server pool configuration</li>
 *   <li>{@code strategy} - Load balancing strategy selection</li>
 *   <li>{@code disruptor} - Ring buffer and wait strategy settings</li>
 *   <li>{@code timeouts} - Request and connection timeouts</li>
 *   <li>{@code retry} - Retry policy configuration</li>
 *   <li>{@code healthCheck} - Health monitoring settings</li>
 *   <li>{@code metrics} - Prometheus metrics configuration</li>
 * </ul>
 *
 * @see fr.lapetina.ollama.loadbalancer.infrastructure.config.LoadBalancerConfig
 * @see fr.lapetina.ollama.loadbalancer.infrastructure.config.ConfigLoader
 */
package fr.lapetina.ollama.loadbalancer.infrastructure.config;
