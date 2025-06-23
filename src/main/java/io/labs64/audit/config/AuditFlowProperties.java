package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "auditflow")
public class AuditFlowProperties {

    private List<PipelineProperties> pipelines;

    public List<PipelineProperties> getPipelines() {
        return pipelines;
    }

    public void setPipelines(List<PipelineProperties> pipelines) {
        this.pipelines = pipelines;
    }

    public static class PipelineProperties {
        private String name;
        private boolean enabled;
        private TransformerProperties transformer;
        private ProcessorProperties processor;

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

        public ProcessorProperties getProcessor() {
            return processor;
        }

        public void setProcessor(ProcessorProperties processor) {
            this.processor = processor;
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

    public static class ProcessorProperties {
        private String name;
        private String clazz; // Renamed from 'class' to avoid keyword conflict
        private Map<String, String> properties;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getClazz() {
            return clazz;
        }

        public void setClazz(String clazz) {
            this.clazz = clazz;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }
    }

}
