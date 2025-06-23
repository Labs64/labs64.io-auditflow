package io.labs64.audit.processors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class LoggingProcessor implements DestinationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LoggingProcessor.class);

    @Override
    public void initialize(Map<String, String> properties) {
    }

    @Override
    public void process(String message) {
        logger.info(message);
    }

}
