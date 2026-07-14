package fr.polytech.mnia;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class AgentParameterCatalog {

    public record OfflineParams(double gamma, double teta, int maxIterations, int horizon, int maxUpdates,
                                int evalIterations, int updatesPerIteration) {}

    public record OnlineParams(double gamma, double teta, double alpha, double epsilon,
                               int planningSteps, int maxEpisodes, int maxStepsPerEpisode,
                               double kappa, int logEveryEpisodes) {}

    public record AgentSettings(OfflineParams offline, OnlineParams online) {}

    private static final String CONFIG_PATH = "config/agent-parameters.properties";
    private static final Properties PROPERTIES = loadProperties();

    private AgentParameterCatalog() {
    }

    public static AgentSettings settings(String envName, AlgorithmId algorithmId) {
        String normalizedEnv = normalize(envName);
        String algo = algorithmId.name();

        OfflineParams offline = new OfflineParams(
                getDouble(normalizedEnv, algo, "offline.gamma", 0.90),
                getDouble(normalizedEnv, algo, "offline.teta", 0.01),
                getInt(normalizedEnv, algo, "offline.maxIterations", 100),
                getInt(normalizedEnv, algo, "offline.horizon", 0),
            getInt(normalizedEnv, algo, "offline.maxUpdates", 100_000),
            getInt(normalizedEnv, algo, "offline.evalIterations", 5),
            getInt(normalizedEnv, algo, "offline.updatesPerIteration", 500)
        );

        OnlineParams online = new OnlineParams(
                getDouble(normalizedEnv, algo, "online.gamma", 0.90),
                getDouble(normalizedEnv, algo, "online.teta", 0.0),
                getDouble(normalizedEnv, algo, "online.alpha", 0.10),
                getDouble(normalizedEnv, algo, "online.epsilon", 0.10),
                getInt(normalizedEnv, algo, "online.planningSteps", 20),
                getInt(normalizedEnv, algo, "online.maxEpisodes", 10_000),
                getInt(normalizedEnv, algo, "online.maxStepsPerEpisode", 100),
                getDouble(normalizedEnv, algo, "online.kappa", 5e-4),
                getInt(normalizedEnv, algo, "online.logEveryEpisodes", 1_000)
        );

        return new AgentSettings(offline, online);
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();

        Path configPath = Paths.get(CONFIG_PATH);
        if (Files.exists(configPath)) {
            try (InputStream inputStream = Files.newInputStream(configPath)) {
                properties.load(inputStream);
                return properties;
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load parameter config from " + configPath.toAbsolutePath(), e);
            }
        }

        try (InputStream inputStream = AgentParameterCatalog.class.getClassLoader().getResourceAsStream("agent-parameters.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load parameter config from classpath resource agent-parameters.properties", e);
        }

        return properties;
    }

    private static double getDouble(String envName, String algo, String field, double defaultValue) {
        String value = lookup(envName, algo, field);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    private static int getInt(String envName, String algo, String field, int defaultValue) {
        String value = lookup(envName, algo, field);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static String lookup(String envName, String algo, String field) {
        String[] keys = new String[] {
                "env." + envName + "." + algo + "." + field,
                "env." + envName + ".default." + field,
                "default." + algo + "." + field,
                "default.default." + field
        };
        for (String key : keys) {
            // Try exact key first
            String value = PROPERTIES.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
            // Fallback to lowercase key to be tolerant with property file casing
            String lowerKey = key.toLowerCase();
            value = PROPERTIES.getProperty(lowerKey);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String normalize(String envName) {
        return envName == null ? "" : envName.toLowerCase();
    }
}
