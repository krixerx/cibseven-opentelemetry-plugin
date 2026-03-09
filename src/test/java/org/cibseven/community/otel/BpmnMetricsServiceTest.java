package org.cibseven.community.otel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link BpmnMetricsService}.
 *
 * <p>Uses the OpenTelemetry SDK in-memory exporter so no real backend is needed.
 */
class BpmnMetricsServiceTest {

  private InMemoryMetricExporter metricExporter;
  private BpmnMetricsService metricsService;

  @BeforeEach
  void setUp() {
    metricExporter = InMemoryMetricExporter.create();

    SdkMeterProvider meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(
            PeriodicMetricReader.builder(metricExporter)
                .setInterval(100, TimeUnit.MILLISECONDS)
                .build())
        .build();

    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(meterProvider)
        .build();

    metricsService = new BpmnMetricsService(openTelemetry);
  }

  @Test
  @DisplayName("onTaskStart increments bpmn.task.started counter")
  void testTaskStarted() throws InterruptedException {
    metricsService.onTaskStart("loan-application", "credit-check", "instance-001");

    Thread.sleep(200);

    List<MetricData> metrics = metricExporter.getFinishedMetricItems();
    assertTrue(
        metrics.stream().anyMatch(m -> m.getName().equals("bpmn.task.started")),
        "Expected bpmn.task.started metric to be exported");
  }

  @Test
  @DisplayName("onTaskEnd increments bpmn.task.ended counter and records duration")
  void testTaskEnded() throws InterruptedException {
    metricsService.onTaskStart("loan-application", "credit-check", "instance-001");
    Thread.sleep(50);
    metricsService.onTaskEnd("loan-application", "credit-check", "instance-001", "completed");

    Thread.sleep(200);

    List<MetricData> metrics = metricExporter.getFinishedMetricItems();

    assertTrue(
        metrics.stream().anyMatch(m -> m.getName().equals("bpmn.task.ended")),
        "Expected bpmn.task.ended metric");
    assertTrue(
        metrics.stream().anyMatch(m -> m.getName().equals("bpmn.task.duration")),
        "Expected bpmn.task.duration histogram");
  }

  @Test
  @DisplayName("onProcessStart and onProcessEnd record process-level metrics")
  void testProcessMetrics() throws InterruptedException {
    metricsService.onProcessStart("loan-application", "instance-002");
    Thread.sleep(50);
    metricsService.onProcessEnd("loan-application", "instance-002", "completed");

    Thread.sleep(200);

    List<MetricData> metrics = metricExporter.getFinishedMetricItems();

    assertTrue(metrics.stream().anyMatch(m -> m.getName().equals("bpmn.process.started")));
    assertTrue(metrics.stream().anyMatch(m -> m.getName().equals("bpmn.process.ended")));
    assertTrue(metrics.stream().anyMatch(m -> m.getName().equals("bpmn.process.duration")));
  }

  @Test
  @DisplayName("rejected outcome is recorded correctly")
  void testRejectedOutcome() throws InterruptedException {
    metricsService.onTaskStart("loan-application", "manual-review", "instance-003");
    metricsService.onTaskEnd("loan-application", "manual-review", "instance-003", "rejected");

    Thread.sleep(200);

    List<MetricData> metrics = metricExporter.getFinishedMetricItems();
    assertTrue(
        metrics.stream()
            .filter(m -> m.getName().equals("bpmn.task.ended"))
            .flatMap(m -> m.getData().getPoints().stream())
            .anyMatch(p -> p.getAttributes().asMap().containsValue("rejected")),
        "Expected a bpmn.task.ended point with outcome=rejected");
  }
}
