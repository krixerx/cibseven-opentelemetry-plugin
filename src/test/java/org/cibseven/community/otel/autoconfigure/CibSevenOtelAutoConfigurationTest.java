package org.cibseven.community.otel.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.cibseven.community.otel.BpmnMetricsService;
import org.cibseven.community.otel.OpenTelemetryProcessEnginePlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CibSevenOtelAutoConfiguration}.
 *
 * <p>Verifies that the auto-configuration bean factory methods return correctly typed,
 * non-null instances when given valid dependencies.
 */
class CibSevenOtelAutoConfigurationTest {

  private CibSevenOtelAutoConfiguration autoConfiguration;
  private OpenTelemetry openTelemetry;

  @BeforeEach
  void setUp() {
    autoConfiguration = new CibSevenOtelAutoConfiguration();
    openTelemetry = OpenTelemetrySdk.builder().build();
  }

  @Test
  @DisplayName("bpmnMetricsService returns a non-null BpmnMetricsService")
  void bpmnMetricsServiceBeanIsCreated() {
    BpmnMetricsService metricsService =
        autoConfiguration.bpmnMetricsService(openTelemetry);

    assertNotNull(metricsService);
    assertInstanceOf(BpmnMetricsService.class, metricsService);
  }

  @Test
  @DisplayName("openTelemetryProcessEnginePlugin returns a non-null plugin instance")
  void pluginBeanIsCreated() {
    BpmnMetricsService metricsService =
        autoConfiguration.bpmnMetricsService(openTelemetry);

    OpenTelemetryProcessEnginePlugin plugin =
        autoConfiguration.openTelemetryProcessEnginePlugin(
            openTelemetry, metricsService);

    assertNotNull(plugin);
    assertInstanceOf(OpenTelemetryProcessEnginePlugin.class, plugin);
  }

  @Test
  @DisplayName("Plugin bean works with a mocked BpmnMetricsService")
  void pluginBeanWorksWithMockedMetrics() {
    BpmnMetricsService mockMetrics = mock(BpmnMetricsService.class);

    OpenTelemetryProcessEnginePlugin plugin =
        autoConfiguration.openTelemetryProcessEnginePlugin(
            openTelemetry, mockMetrics);

    assertNotNull(plugin);
  }
}
