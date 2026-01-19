package fr.lapetina.ollama.loadbalancer;

import fr.lapetina.ollama.loadbalancer.api.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Main entry point for the Ollama Load Balancer.
 */
public class OllamaLoadBalancerApplication implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OllamaLoadBalancerApplication.class);

    private final PipelineFactory factory;
    private final HttpServer httpServer;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public OllamaLoadBalancerApplication(String configPath) throws Exception {
        log.info("Starting Ollama Load Balancer...");

        // Create and start the pipeline factory
        this.factory = PipelineFactory.create(configPath).start();

        // Create HTTP server
        this.httpServer = new HttpServer(
                factory.getConfig().getServer().getPort(),
                factory.getConfig().getServer().getBacklog(),
                factory.getPipeline(),
                factory.getNodeRegistry(),
                factory.getMetricsRegistry(),
                factory.getConfigLoader()
        );

        log.info("Ollama Load Balancer initialized");
    }

    public void start() {
        httpServer.start();
        log.info("Ollama Load Balancer started on port {}",
                factory.getConfig().getServer().getPort());
    }

    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    public void requestShutdown() {
        shutdownLatch.countDown();
    }

    public PipelineFactory getFactory() {
        return factory;
    }

    @Override
    public void close() {
        log.info("Shutting down Ollama Load Balancer...");

        try {
            httpServer.close();
        } catch (Exception e) {
            log.warn("Error closing HTTP server", e);
        }

        try {
            factory.close();
        } catch (Exception e) {
            log.warn("Error closing factory", e);
        }

        log.info("Ollama Load Balancer shut down");
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.yaml";

        try {
            OllamaLoadBalancerApplication app = new OllamaLoadBalancerApplication(configPath);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                app.requestShutdown();
                app.close();
            }));

            app.start();
            app.awaitShutdown();

        } catch (Exception e) {
            log.error("Failed to start Ollama Load Balancer", e);
            System.exit(1);
        }
    }
}
