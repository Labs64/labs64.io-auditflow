package io.labs64.audit.processors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.Map;
import java.util.Optional;

public class LoggingProcessor implements DestinationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LoggingProcessor.class);

    private Level logLevel;

    @Override
    public void initialize(Map<String, String> properties) {
        this.logLevel = Optional.ofNullable(properties.get("log-level"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(levelStr -> {
                    try {
                        return Level.valueOf(levelStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid log level '{}' configured. Defaulting to INFO.", levelStr);
                        return Level.INFO;
                    }
                })
                .orElseGet(() -> {
                    logger.warn("Log level not provided in properties. Using default: INFO.");
                    return Level.INFO;
                });
        logger.info("LoggingProcessor initialized with log level: {}", this.logLevel);
    }

    @Override
    public void process(String message) {
        logger.atLevel(logLevel).log(message);
    }

}
