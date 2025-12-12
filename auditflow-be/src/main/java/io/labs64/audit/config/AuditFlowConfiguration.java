package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConfigurationProperties(prefix = "")
public class AuditFlowConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AuditFlowConfiguration.class);

    private List<PipelineProperties> pipelines = new ArrayList<>();;

    public List<PipelineProperties> getPipelines() {
        return pipelines;
    }

    public void setPipelines(List<PipelineProperties> pipelines) {
        this.pipelines = pipelines;
    }

    @PostConstruct
    public void logConfiguration() {
        logger.debug("Loaded {} pipelines at AuditFlowConfiguration", pipelines.size());
    }

    public static class PipelineProperties {
        private String name;
        private boolean enabled;
        private TransformerProperties transformer;
        private SinkProperties sink;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public TransformerProperties getTransformer() {
            return transformer;
        }

        public void setTransformer(TransformerProperties transformer) {
            this.transformer = transformer;
        }

        public SinkProperties getSink() {
            return sink;
        }

        public void setSink(SinkProperties sink) {
            this.sink = sink;
        }
    }

    public static class TransformerProperties {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class SinkProperties {
        private String name;
        private Map<String, String> properties;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }


        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }
    }

}
