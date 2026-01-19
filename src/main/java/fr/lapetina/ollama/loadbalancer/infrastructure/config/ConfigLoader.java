package fr.lapetina.ollama.loadbalancer.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Configuration loader with hot-reload support.
 *
 * Supports:
 * - Loading from classpath or file system
 * - File watching for automatic reload
 * - Listener notification on changes
 */
public final class ConfigLoader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private final AtomicReference<LoadBalancerConfig> currentConfig = new AtomicReference<>();
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Path configPath;
    private final Yaml yaml;

    private WatchService watchService;
    private ScheduledExecutorService watchExecutor;
    private volatile long lastModified;

    public ConfigLoader(String configPath) {
        this.configPath = Paths.get(configPath);
        LoaderOptions loaderOptions = new LoaderOptions();
        this.yaml = new Yaml(new Constructor(LoadBalancerConfig.class, loaderOptions));
    }

    /**
     * Loads configuration from file or classpath.
     *
     * @return The loaded configuration
     * @throws ConfigurationException if loading fails
     */
    public LoadBalancerConfig load() {
        LoadBalancerConfig config = loadFromPath();
        LoadBalancerConfig previous = currentConfig.getAndSet(config);
        notifyListeners(previous, config);
        return config;
    }

    private LoadBalancerConfig loadFromPath() {
        // Try file system first
        if (Files.exists(configPath)) {
            return loadFromFile(configPath);
        }

        // Try classpath
        String classpathResource = configPath.toString();
        if (classpathResource.startsWith("/")) {
            classpathResource = classpathResource.substring(1);
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (is != null) {
                log.info("Loading configuration from classpath: {}", classpathResource);
                return yaml.load(is);
            }
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load from classpath: " + classpathResource, e);
        }

        throw new ConfigurationException("Configuration file not found: " + configPath);
    }

    private LoadBalancerConfig loadFromFile(Path path) {
        try {
            log.info("Loading configuration from file: {}", path);
            lastModified = Files.getLastModifiedTime(path).toMillis();
            return yaml.load(Files.newInputStream(path));
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load configuration from: " + path, e);
        }
    }

    /**
     * Loads configuration from an input stream.
     */
    public LoadBalancerConfig loadFromStream(InputStream inputStream) {
        LoadBalancerConfig config = yaml.load(inputStream);
        LoadBalancerConfig previous = currentConfig.getAndSet(config);
        notifyListeners(previous, config);
        return config;
    }

    /**
     * Returns the current configuration.
     */
    public LoadBalancerConfig getCurrentConfig() {
        return currentConfig.get();
    }

    /**
     * Starts watching the configuration file for changes.
     */
    public void startWatching() {
        if (!Files.exists(configPath)) {
            log.warn("Config file does not exist, hot reload disabled: {}", configPath);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path parent = configPath.getParent();
            if (parent == null) {
                parent = Paths.get(".");
            }
            parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            watchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "config-watcher");
                t.setDaemon(true);
                return t;
            });

            watchExecutor.scheduleWithFixedDelay(this::checkForChanges, 1, 1, TimeUnit.SECONDS);

            log.info("Configuration hot-reload enabled for: {}", configPath);

        } catch (IOException e) {
            log.error("Failed to start config watcher", e);
        }
    }

    private void checkForChanges() {
        try {
            WatchKey key = watchService.poll();
            if (key == null) {
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                Path changed = (Path) event.context();
                if (changed.equals(configPath.getFileName())) {
                    // Debounce - check if file actually changed
                    long newLastModified = Files.getLastModifiedTime(configPath).toMillis();
                    if (newLastModified > lastModified) {
                        log.info("Configuration file changed, reloading...");
                        reload();
                    }
                }
            }

            key.reset();
        } catch (Exception e) {
            log.error("Error checking for config changes", e);
        }
    }

    /**
     * Forces a configuration reload.
     */
    public LoadBalancerConfig reload() {
        try {
            return load();
        } catch (Exception e) {
            log.error("Failed to reload configuration, keeping current", e);
            return currentConfig.get();
        }
    }

    /**
     * Adds a listener for configuration changes.
     */
    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a configuration change listener.
     */
    public void removeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(LoadBalancerConfig oldConfig, LoadBalancerConfig newConfig) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChanged(oldConfig, newConfig);
            } catch (Exception e) {
                log.error("Error notifying config change listener", e);
            }
        }
    }

    @Override
    public void close() {
        if (watchExecutor != null) {
            watchExecutor.shutdown();
            try {
                watchExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Error closing watch service", e);
            }
        }
    }

    /**
     * Creates a default configuration.
     */
    public static LoadBalancerConfig createDefault() {
        return new LoadBalancerConfig();
    }

    /**
     * Exception for configuration errors.
     */
    public static class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
