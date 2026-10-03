package io.labs64.audit.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /actuator/pipelines/<tenantId>/dry-run}: the write half of {@link PipelinesEndpoint}.
 * A plain controller because actuator write operations only bind flat string values, and a dry run
 * takes events and an optional tenant document. It lives under {@code /actuator} so it shares the
 * actuator's exposure: reachable in-cluster (port-forward), never routed by the gateway.
 *
 * <p>200 with {@code status} {@code success} or {@code failed} (fixture cases did not match); 400
 * with {@code status} {@code error} for a bad request (unknown tenant, invalid document, too many
 * events).</p>
 */
@RestController
public class PipelineDryRunController {

    /** Request body; send either {@code events} or {@code cases}. */
    public record DryRunRequest(List<Map<String, Object>> events, List<Map<String, Object>> cases,
                                Map<String, Object> tenant, Boolean transform) {
    }

    private final PipelinesEndpoint endpoint;

    public PipelineDryRunController(PipelinesEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @PostMapping(path = "/actuator/pipelines/{tenantId}/dry-run", consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<Map<String, Object>> dryRun(@PathVariable String tenantId, @RequestBody DryRunRequest request) {
        Map<String, Object> result = endpoint.dryRun(tenantId, request.events(), request.cases(), request.tenant(),
                request.transform());
        return "error".equals(result.get("status")) ? ResponseEntity.badRequest().body(result) : ResponseEntity.ok(result);
    }
}
