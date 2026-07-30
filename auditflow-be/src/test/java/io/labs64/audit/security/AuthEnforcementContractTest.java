package io.labs64.audit.security;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

import io.labs64.authcontext.test.AuthEnforcementContract;
import io.labs64.authcontext.test.ModulePepHarness;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Item 2 (roadmap): every operation protected by effective OpenAPI
 * {@code security} or {@code x-labs64.auth} in the canonical spec is called
 * without credentials and must be refused.
 *
 * <p>Cases come from {@code auditflow-api/.../openapi-audit-v1.yaml} — the same
 * source the Cerbos policies, the gateway routes and the generated public-path
 * list come from. A new protected operation becomes a new case automatically;
 * removing the spec's protection, or failing to parse it, trips the
 * completeness guard rather than quietly emptying the suite.
 *
 * <p>Controllers are discovered and the filter is built from the real
 * {@code application.yml} — see {@link ModulePepHarness}. A 404 fails: an
 * unmapped route means the call never reached an enforcement point.
 *
 * <p>Scope: module-layer PEP. The gateway edge is proven separately by the
 * generated suite in {@code labs64.io-tests}; both layers must hold.
 */
class AuthEnforcementContractTest {

    /** The canonical contract, not a build artifact — a stale generated file must not shrink this suite. */
    private static final Path SPEC = Path.of("..", "auditflow-api", "src", "main", "resources", "openapi",
            "openapi-audit-v1.yaml");

    private final MockMvc mockMvc = ModulePepHarness.withProductionAuthFilter("io.labs64.audit.controller");

    @TestFactory
    Stream<DynamicTest> everyProtectedOperationRefusesAnonymousCallers() throws IOException {
        return AuthEnforcementContract.rejectsAnonymousAccess(SPEC, (method, path) ->
                mockMvc.perform(request(HttpMethod.valueOf(method), path))
                        .andReturn().getResponse().getStatus());
    }
}
