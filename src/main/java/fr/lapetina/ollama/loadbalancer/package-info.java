/**
 * Ollama Load Balancer - High-performance load balancer for Ollama LLM inference servers.
 *
 * <p>This library provides a configurable load balancer that distributes inference requests
 * across a pool of Ollama servers using the LMAX Disruptor for ultra-low latency event processing.
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.PipelineFactory} - Main entry point for creating
 *       a fully-configured pipeline from YAML configuration</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.OllamaLoadBalancerApplication} - Standalone HTTP server
 *       with Ollama-compatible API</li>
 * </ul>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * try (PipelineFactory factory = PipelineFactory.create("config.yaml").start()) {
 *     DisruptorPipeline pipeline = factory.getPipeline();
 *
 *     InferenceRequest request = InferenceRequest.ofPrompt("llama2", "Hello!");
 *     CompletableFuture<InferenceResponse> future = pipeline.submit(request);
 *
 *     InferenceResponse response = future.get();
 *     System.out.println(response.response());
 * }
 * }</pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Multiple load balancing strategies (round-robin, weighted, least-in-flight, random, model-aware)</li>
 *   <li>Circuit breaker pattern for fault tolerance</li>
 *   <li>Hot-reload configuration without restart</li>
 *   <li>Micrometer metrics with Prometheus export</li>
 *   <li>Backpressure handling via ring buffer</li>
 * </ul>
 *
 * @see fr.lapetina.ollama.loadbalancer.PipelineFactory
 * @see fr.lapetina.ollama.loadbalancer.disruptor.DisruptorPipeline
 */
package fr.lapetina.ollama.loadbalancer;
