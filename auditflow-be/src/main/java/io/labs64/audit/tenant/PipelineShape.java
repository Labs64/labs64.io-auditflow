package io.labs64.audit.tenant;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.StepProperties;

/**
 * Structural rules for the two pipeline shapes, checked when a tenant document is parsed so a wrong
 * one is refused as a whole instead of failing event by event: a pipeline has either
 * {@code transformer(s)} + {@code sink} or {@code steps}, never both, and every step has a unique,
 * valid name and a sink. A step's name is the key of what its sink returns in the event's context.
 */
final class PipelineShape {

    /** The same identifier rule as transformer and sink ids (guardrail: keep it equal across Java and Python). */
    private static final Pattern STEP_NAME = Pattern.compile("^[a-zA-Z0-9_]+$");

    private PipelineShape() {
    }

    static void check(List<PipelineProperties> pipelines) {
        for (PipelineProperties p : pipelines) {
            if (p == null || !p.hasSteps()) {
                continue;
            }
            String pipeline = "pipeline '" + p.getName() + "'";
            boolean topLevel = p.getSink() != null || p.getTransformer() != null
                    || (p.getTransformers() != null && !p.getTransformers().isEmpty());
            if (topLevel) {
                throw new IllegalArgumentException(pipeline
                        + " has both 'steps' and a top-level 'transformer'/'transformers'/'sink'; use one shape");
            }
            Set<String> names = new HashSet<>();
            for (StepProperties step : p.getSteps()) {
                if (step == null || !StringUtils.hasText(step.getName()) || !STEP_NAME.matcher(step.getName()).matches()) {
                    throw new IllegalArgumentException(pipeline + " has a step without a valid name ("
                            + STEP_NAME.pattern() + ")");
                }
                if (!names.add(step.getName())) {
                    throw new IllegalArgumentException(pipeline + " has two steps named '" + step.getName() + "'");
                }
                if (step.getSink() == null || !StringUtils.hasText(step.getSink().getName())) {
                    throw new IllegalArgumentException(pipeline + ", step '" + step.getName() + "' has no sink");
                }
            }
        }
    }
}
