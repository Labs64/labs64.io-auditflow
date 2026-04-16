package io.labs64.audit.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for KubernetesSinkDiscovery.
 *
 * Tasks 1 & 2: Bug condition exploration + preservation tests.
 *
 * Bug condition: getSinkUrl() returns null instead of throwing IllegalStateException
 * when the Kubernetes service is not found or the API throws.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KubernetesSinkDiscoveryTest {

    @Mock
    private KubernetesClient kubernetesClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOps;

    @Mock
    private ServiceResource<Service> serviceResource;

    private KubernetesSinkDiscovery discovery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        discovery = new KubernetesSinkDiscovery(kubernetesClient);
        ReflectionTestUtils.setField(discovery, "serviceName", "auditflow-sink");
        ReflectionTestUtils.setField(discovery, "namespace", "default");
        ReflectionTestUtils.setField(discovery, "port", 8082);

        when(kubernetesClient.services()).thenReturn(serviceOps);
        when(serviceOps.inNamespace("default")).thenReturn(serviceOps);
        when(serviceOps.withName("auditflow-sink")).thenReturn(serviceResource);
    }

    // -------------------------------------------------------------------------
    // Task 1: Bug condition exploration tests
    // These FAIL on unfixed code (null returned instead of exception thrown)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Bug condition A: getSinkUrl() throws IllegalStateException when service not found (returns null from K8s)")
    void shouldThrowWhenServiceNotFound() {
        // Arrange: Kubernetes API returns null (service does not exist)
        when(serviceResource.get()).thenReturn(null);

        // Act & Assert: expect IllegalStateException — FAILS on unfixed code (null returned)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> discovery.getSinkUrl(),
                "Expected IllegalStateException when Kubernetes service is not found");

        assertTrue(ex.getMessage().contains("auditflow-sink"),
                "Exception message should contain the service name, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("Bug condition B: getSinkUrl() throws IllegalStateException when Kubernetes API throws")
    @SuppressWarnings("unchecked")
    void shouldThrowWhenKubernetesApiThrows() {
        // Arrange: Kubernetes API throws a runtime exception
        when(serviceResource.get()).thenThrow(new RuntimeException("k8s unavailable"));

        // Act & Assert: expect IllegalStateException — FAILS on unfixed code (exception swallowed, null returned)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> discovery.getSinkUrl(),
                "Expected IllegalStateException when Kubernetes API throws");

        assertTrue(ex.getMessage().contains("Kubernetes sink discovery failed") ||
                        ex.getMessage().contains("k8s unavailable"),
                "Exception message should describe the failure, was: " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Task 2: Preservation tests
    // These PASS on both unfixed and fixed code — they document the happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Preservation: getSinkUrl() returns correct URL when service exists")
    void shouldReturnCorrectUrlWhenServiceExists() {
        // Arrange: Kubernetes API returns a valid service
        Service mockService = mock(Service.class);
        when(serviceResource.get()).thenReturn(mockService);

        // Act
        String url = discovery.getSinkUrl();

        // Assert: URL follows the expected pattern
        assertEquals("http://auditflow-sink.default.svc.cluster.local:8082", url);
    }

    @ParameterizedTest(name = "serviceName={0}, namespace={1}, port={2}")
    @CsvSource({
            "auditflow-sink, default, 8082",
            "my-sink-service, production, 9090",
            "sink, kube-system, 80"
    })
    @DisplayName("Preservation: getSinkUrl() URL format holds for various service/namespace/port combinations")
    @SuppressWarnings("unchecked")
    void shouldReturnCorrectUrlForVariousConfigurations(String serviceName, String namespace, int port) {
        // Arrange: reconfigure the discovery bean fields
        ReflectionTestUtils.setField(discovery, "serviceName", serviceName);
        ReflectionTestUtils.setField(discovery, "namespace", namespace);
        ReflectionTestUtils.setField(discovery, "port", port);

        // Re-stub with new values
        when(kubernetesClient.services()).thenReturn(serviceOps);
        when(serviceOps.inNamespace(namespace)).thenReturn(serviceOps);
        when(serviceOps.withName(serviceName)).thenReturn(serviceResource);

        Service mockService = mock(Service.class);
        when(serviceResource.get()).thenReturn(mockService);

        // Act
        String url = discovery.getSinkUrl();

        // Assert: URL follows the expected pattern
        String expectedUrl = String.format("http://%s.%s.svc.cluster.local:%d", serviceName, namespace, port);
        assertEquals(expectedUrl, url,
                "URL should follow http://<serviceName>.<namespace>.svc.cluster.local:<port> pattern");
    }
}
