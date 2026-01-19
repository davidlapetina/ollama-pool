/**
 * LMAX Disruptor-based pipeline for processing inference requests.
 *
 * <p>This package contains the core event processing pipeline built on the LMAX Disruptor
 * library. The Disruptor provides significant advantages over traditional {@code ExecutorService}:
 *
 * <ul>
 *   <li><b>Zero Allocation</b> - Pre-allocated ring buffer eliminates GC during operation</li>
 *   <li><b>Cache-Friendly</b> - Sequential memory access patterns improve CPU cache utilization</li>
 *   <li><b>Lock-Free</b> - CAS-based publishing instead of synchronized queues</li>
 *   <li><b>Batching</b> - Handlers can optimize for batch operations</li>
 *   <li><b>Predictable Latency</b> - No GC pauses or lock contention</li>
 * </ul>
 *
 * <h2>Pipeline Stages</h2>
 * <p>Events flow through handlers in sequence:
 * <pre>
 * Validation → Node Selection → Rate Limit → HTTP Dispatch → Metrics → Completion
 * </pre>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.disruptor.DisruptorPipeline} - Main pipeline orchestrator</li>
 *   <li>{@link fr.lapetina.ollama.loadbalancer.disruptor.exception.BackpressureException} - Thrown when ring buffer is full</li>
 * </ul>
 *
 * @see fr.lapetina.ollama.loadbalancer.disruptor.DisruptorPipeline
 * @see com.lmax.disruptor.dsl.Disruptor
 */
package fr.lapetina.ollama.loadbalancer.disruptor;
