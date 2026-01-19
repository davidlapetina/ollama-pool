# Ollama Load Balancer

A high-performance, configurable load balancer for dispatching LLM inference requests to a pool of Ollama servers. Built with LMAX Disruptor for ultra-low latency event processing.

## Features

- **Multiple Load Balancing Strategies**: Round-robin, weighted round-robin, least-in-flight, random, and model-aware routing
- **LMAX Disruptor Pipeline**: Lock-free, GC-friendly event processing for consistent low latency
- **Circuit Breaker Pattern**: Automatic failure detection and recovery per node
- **Hot-Reload Configuration**: Update nodes and strategies without restart
- **Comprehensive Observability**: Micrometer metrics, structured logging, health endpoints
- **Backpressure Handling**: Graceful load shedding when at capacity

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.ollama-pool</groupId>
    <artifactId>ollama-load-balancer</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.ollama-pool:ollama-load-balancer:1.0.0'
```

## Library Usage

Use the `PipelineFactory` to obtain a fully-configured `DisruptorPipeline` from YAML configuration:

```java
import fr.lapetina.ollama.loadbalancer.PipelineFactory;
import fr.lapetina.ollama.loadbalancer.disruptor.DisruptorPipeline;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceRequest;
import fr.lapetina.ollama.loadbalancer.domain.model.InferenceResponse;

// Create and start the factory from configuration
try (PipelineFactory factory = PipelineFactory.create("config.yaml").start()) {
    DisruptorPipeline pipeline = factory.getPipeline();

    // Submit inference requests
    InferenceRequest request = InferenceRequest.ofPrompt("llama2", "Why is the sky blue?");
    CompletableFuture<InferenceResponse> future = pipeline.submit(request);

    // Handle the response
    InferenceResponse response = future.get();
    System.out.println(response.response());
}
```

### Customizing Components

You can access individual components for advanced use cases:

```java
PipelineFactory factory = PipelineFactory.create("config.yaml").start();

// Access the node registry for monitoring
NodeRegistry nodes = factory.getNodeRegistry();
nodes.getAllNodes().forEach(node ->
    System.out.println(node.getId() + ": " + node.getHealth()));

// Access metrics for Prometheus scraping
MetricsRegistry metrics = factory.getMetricsRegistry();
String prometheus = metrics.scrape();

// Change load balancing strategy at runtime
pipeline.setStrategy(StrategyFactory.create("round-robin").orElseThrow());
```

### Running as Standalone Server

For a complete HTTP server with Ollama-compatible API:

```java
import fr.lapetina.ollama.loadbalancer.OllamaLoadBalancerApplication;

public class Main {
    public static void main(String[] args) throws Exception {
        try (var app = new OllamaLoadBalancerApplication("config.yaml")) {
            app.start();
            app.awaitShutdown();
        }
    }
}
```

## Quick Start

```bash
# Build
mvn clean package

# Run with default config
java -jar target/ollama-load-balancer-1.0.0.jar config.yaml

# Or with custom config
java -jar target/ollama-load-balancer-1.0.0.jar /path/to/config.yaml
```

## API Endpoints

### Inference

```bash
# Ollama-compatible generate endpoint
curl -X POST http://localhost:8080/api/generate \
  -H "Content-Type: application/json" \
  -d '{"model": "llama2", "prompt": "Why is the sky blue?"}'

# Chat endpoint
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama2",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'
```

### Health & Metrics

```bash
# Health check
curl http://localhost:8080/health

# Prometheus metrics
curl http://localhost:8080/metrics
```

### Admin

```bash
# List nodes
curl http://localhost:8080/admin/nodes

# Disable a node
curl -X POST http://localhost:8080/admin/nodes/node-1/disable

# Change strategy
curl -X POST http://localhost:8080/admin/strategy \
  -H "Content-Type: application/json" \
  -d '{"strategy": "least-in-flight"}'

# Reload configuration
curl -X POST http://localhost:8080/admin/reload
```

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        HTTP API Layer                                │
│   /v1/inference  /health  /metrics  /admin/*                        │
└─────────────────────────────┬────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    LMAX Disruptor Pipeline                           │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────────┐ │
│  │ Validation │─▶│  Node      │─▶│ Rate       │─▶│ HTTP Dispatch  │ │
│  │ Handler    │  │ Selection  │  │ Limiting   │  │ Handler        │ │
│  └────────────┘  └────────────┘  └────────────┘  └────────────────┘ │
│         │               │               │               │            │
│         ▼               ▼               ▼               ▼            │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │              Metrics Handler  →  Completion Handler            │ │
│  └────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                      Ollama Node Pool                                │
│   ┌─────────┐   ┌─────────┐   ┌─────────┐                           │
│   │ Node 1  │   │ Node 2  │   │ Node 3  │   ...                     │
│   └─────────┘   └─────────┘   └─────────┘                           │
└──────────────────────────────────────────────────────────────────────┘
```

## Configuration Reference

See `src/main/resources/config.yaml` for a complete example.

### Key Configuration Options

| Section | Parameter | Description | Default |
|---------|-----------|-------------|---------|
| server.port | API server port | 8080 |
| strategy.type | Load balancing strategy | least-in-flight |
| disruptor.ringBufferSize | Ring buffer size (power of 2) | 1024 |
| disruptor.waitStrategy | Disruptor wait strategy | yielding |
| disruptor.maxGlobalInFlight | Max concurrent requests | 1000 |
| timeouts.requestTimeoutMs | Overall request timeout | 120000 |
| retry.maxRetries | Max retry attempts | 3 |
| healthCheck.intervalMs | Health check interval | 30000 |

## Load Balancing Strategies

| Strategy | Description | Best For |
|----------|-------------|----------|
| `round-robin` | Cycles through nodes in order | Homogeneous nodes, simple setup |
| `weighted-round-robin` | Respects node weights | Heterogeneous node capacities |
| `least-in-flight` | Selects least loaded node | Variable response times |
| `random` | Random selection | Simple, low overhead |
| `model-aware` | Routes by model availability | Multi-model deployments |

---

# Performance Tuning Guide

## Why LMAX Disruptor?

The Disruptor provides significant advantages over traditional `ExecutorService`:

1. **Zero Allocation**: Pre-allocated ring buffer eliminates GC during operation
2. **Cache-Friendly**: Sequential memory access patterns improve CPU cache utilization
3. **Lock-Free**: CAS-based publishing instead of synchronized queues
4. **Batching**: Handlers can optimize for batch operations
5. **Predictable Latency**: No GC pauses or lock contention

### Benchmarks (typical, your results may vary)

| Metric | ExecutorService | Disruptor |
|--------|-----------------|-----------|
| p99 Latency | 2-5ms | 0.1-0.3ms |
| p99.9 Latency | 10-50ms | 0.5-1ms |
| Throughput | 50-100k/s | 500k-1M/s |
| GC Impact | Significant | Minimal |

## Tuning Parameters

### Ring Buffer Size

```yaml
disruptor:
  ringBufferSize: 1024  # Must be power of 2
```

- **Too Small**: Backpressure triggers early, requests rejected
- **Too Large**: Memory waste, longer warm-up time
- **Recommendation**: Start with 1024, increase to 4096 or 8192 for high throughput

### Wait Strategy

```yaml
disruptor:
  waitStrategy: yielding
```

| Strategy | CPU Usage | Latency | Use Case |
|----------|-----------|---------|----------|
| `blocking` | Low | Higher | Shared/cloud environments |
| `sleeping` | Medium | Medium | General purpose |
| `yielding` | High | Low | Dedicated machines |
| `busy-spin` | Maximum | Lowest | Latency-critical, dedicated cores |

### Global Concurrency Limits

```yaml
disruptor:
  maxGlobalInFlight: 1000
```

Set based on:
- Total capacity across all nodes
- Memory available for in-flight requests
- Downstream service limits

### Per-Node Configuration

```yaml
nodes:
  - id: node-1
    maxConcurrentRequests: 10  # Based on GPU memory and model size
    weight: 2                   # For weighted strategies
```

## JVM Tuning

### Recommended JVM Flags

```bash
java \
  -Xms2g -Xmx2g \                    # Fixed heap to avoid resizing
  -XX:+UseZGC \                       # Low-pause GC (Java 17+)
  -XX:+AlwaysPreTouch \               # Pre-touch memory pages
  -XX:+UseNUMA \                      # NUMA-aware allocation
  -Djava.lang.Integer.IntegerCache.high=10000 \  # Cache more integers
  -jar ollama-load-balancer.jar
```

### For Ultra-Low Latency

```bash
java \
  -Xms4g -Xmx4g \
  -XX:+UseZGC \
  -XX:ZCollectionInterval=0 \         # Disable proactive GC
  -XX:+AlwaysPreTouch \
  -XX:+UseTransparentHugePages \      # THP for large heaps
  -XX:-UseBiasedLocking \             # Disable biased locking
  -jar ollama-load-balancer.jar
```

## Network Tuning

### Linux Kernel Parameters

```bash
# Increase socket buffer sizes
sysctl -w net.core.rmem_max=16777216
sysctl -w net.core.wmem_max=16777216
sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"

# Increase connection backlog
sysctl -w net.core.somaxconn=65535
sysctl -w net.ipv4.tcp_max_syn_backlog=65535

# Enable TCP Fast Open
sysctl -w net.ipv4.tcp_fastopen=3
```

## Monitoring Key Metrics

### Prometheus Queries

```promql
# Request rate
rate(ollama_lb_requests_total[1m])

# p99 latency by model
histogram_quantile(0.99, rate(ollama_lb_request_latency_bucket[5m]))

# Error rate
rate(ollama_lb_errors_total[1m])

# Node utilization
ollama_lb_node_inflight / on(node) group_left ollama_lb_node_max_concurrent

# Ring buffer saturation
1 - (ollama_lb_ringbuffer_remaining / 1024)
```

### Alert Rules

```yaml
groups:
  - name: ollama-lb
    rules:
      - alert: HighLatency
        expr: histogram_quantile(0.99, rate(ollama_lb_request_latency_bucket[5m])) > 5
        for: 5m

      - alert: HighErrorRate
        expr: rate(ollama_lb_errors_total[5m]) > 0.01
        for: 5m

      - alert: NodeDown
        expr: ollama_lb_node_health == 0
        for: 1m

      - alert: BackpressureRisk
        expr: ollama_lb_ringbuffer_remaining < 100
        for: 1m
```

## Scaling Guidelines

### Vertical Scaling

1. Increase `ringBufferSize` for more in-flight capacity
2. Add more nodes to the pool
3. Increase `maxConcurrentRequests` per node (if GPU allows)

### Horizontal Scaling

For multiple load balancer instances:
1. Use external load balancer (nginx, HAProxy) in front
2. Each instance maintains its own node pool state
3. Consider sticky sessions for stateful models

## Troubleshooting

### High Latency

1. Check if ring buffer is near full (`ollama_lb_ringbuffer_remaining`)
2. Verify nodes aren't at capacity
3. Review circuit breaker states
4. Consider switching to `least-in-flight` strategy

### Backpressure Errors

1. Increase `ringBufferSize`
2. Add more nodes
3. Reduce `maxGlobalInFlight` to match actual capacity
4. Implement client-side retry with backoff

### Memory Issues

1. Reduce `ringBufferSize`
2. Check for memory leaks in custom handlers
3. Review JVM heap settings
4. Enable GC logging: `-Xlog:gc*`

---

## Thread Safety Validation

All components have been designed for thread safety:

- **OllamaNode**: Uses `AtomicInteger` for in-flight tracking, `AtomicReference` for health
- **Strategies**: Use `AtomicInteger` counters, `ConcurrentHashMap` for state
- **CircuitBreaker**: Full atomic state machine implementation
- **NodeRegistry**: `ConcurrentHashMap` storage, `CopyOnWriteArrayList` for listeners
- **DisruptorPipeline**: Thread-safe by design (single writer per handler)

## Disruptor Usage Validation

The implementation follows Disruptor best practices:

1. **Event Reuse**: Events are cleared and reinitialized, not recreated
2. **Handler Isolation**: Each handler processes independently
3. **Sequence Barriers**: Proper handler ordering with `.then()`
4. **Exception Handling**: Custom exception handler prevents pipeline stalls
5. **Graceful Shutdown**: Timeout-based shutdown with fallback to halt

## Failure Scenario Validation

| Scenario | Behavior |
|----------|----------|
| Node failure | Circuit breaker opens, traffic redirected |
| All nodes down | Returns NO_AVAILABLE_NODE error |
| Ring buffer full | Throws BackpressureException |
| Request timeout | Returns TIMEOUT error, releases slot |
| Validation failure | Event skipped, error returned to caller |
| Config reload | Nodes hot-swapped, strategy updated |

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.
