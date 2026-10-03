package io.labs64.audit.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import io.labs64.audit.service.ConditionEvaluator;
import io.labs64.audit.service.PipelineDryRun;

/**
 * Every repository tenant file with fixtures ({@code tenants/fixtures/<tenantId>.yaml}) routes each
 * fixture event to exactly the expected pipelines and has no configuration warnings. A condition
 * change that silently stops matching fails here, not in production.
 */
class TenantFixturesTest {

    private static final Path TENANTS = Path.of("..", "tenants");

    @TestFactory
    Stream<DynamicTest> repositoryTenantFilesPassTheirFixtures() throws IOException {
        Path fixtures = TENANTS.resolve("fixtures");
        assertTrue(Files.isDirectory(fixtures), "missing " + fixtures.toAbsolutePath());
        ObjectMapper json = new ObjectMapper();
        TenantConfigParser parser = new TenantConfigParser(json);
        PipelineDryRun dryRun = new PipelineDryRun(new ConditionEvaluator(), null, new TenantPipelineRegistry(), json,
                20, Duration.ofHours(24));
        List<Path> files;
        try (Stream<Path> list = Files.list(fixtures)) {
            files = list.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no fixture files in " + fixtures);
        return files.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            String tenantId = file.getFileName().toString().replaceFirst("\\.yaml$", "");
            TenantConfig config = parser.parse(Files.readString(TENANTS.resolve(tenantId + ".yaml")));
            JsonNode root = new YAMLMapper().readTree(file.toFile());
            PipelineDryRun.Report report = dryRun.run(tenantId, config, PipelineDryRun.casesFrom(root), false);
            assertEquals(List.of(), report.warnings(), "configuration warnings");
            assertEquals(List.of(), PipelineDryRun.failures(report), "fixture failures");
        }));
    }
}
