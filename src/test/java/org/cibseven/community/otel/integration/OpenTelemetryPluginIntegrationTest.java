package org.cibseven.community.otel.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.community.otel.BpmnMetricsService;
import org.cibseven.community.otel.OpenTelemetryExecutionListener;
import org.cibseven.community.otel.OpenTelemetryProcessEnginePlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration test that boots an embedded CIB seven engine with the OpenTelemetry plugin
 * registered, deploys a simple BPMN process, runs it, and verifies that spans and metrics are
 * correctly recorded end-to-end.
 *
 * <p>This test uses a standalone in-memory process engine (no Spring context needed) combined
 * with in-memory OpenTelemetry exporters to capture telemetry without external infrastructure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenTelemetryPluginIntegrationTest {

  private ProcessEngine processEngine;
  private InMemorySpanExporter spanExporter;
  private InMemoryMetricExporter metricExporter;

  @BeforeAll
  void setUp() {
    spanExporter = InMemorySpanExporter.create();
    metricExporter = InMemoryMetricExporter.create();

    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
        .build();

    SdkMeterProvider meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(
            PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofMillis(100))
                .build())
        .build();

    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setMeterProvider(meterProvider)
        .build();

    BpmnMetricsService metricsService = new BpmnMetricsService(openTelemetry);
    OpenTelemetryProcessEnginePlugin plugin =
        new OpenTelemetryProcessEnginePlugin(openTelemetry, metricsService);

    StandaloneInMemProcessEngineConfiguration config =
        new StandaloneInMemProcessEngineConfiguration();
    config.setJdbcUrl("jdbc:h2:mem:otel-integration-test;DB_CLOSE_DELAY=-1");
    config.setJobExecutorActivate(false);
    config.setEnforceHistoryTimeToLive(false);
    config.getProcessEnginePlugins().add(plugin);

    processEngine = config.buildProcessEngine();

    RepositoryService repositoryService = processEngine.getRepositoryService();
    repositoryService.createDeployment()
        .addClasspathResource("test-process.bpmn")
        .name("integration-test-deployment")
        .deploy();
  }

  @AfterAll
  void tearDown() throws Exception {
    clearStaticSpanMaps();
    if (processEngine != null) {
      processEngine.close();
    }
  }

  @Test
  @DisplayName("Process execution creates spans for BPMN activities")
  void processExecutionCreatesSpans() {
    RuntimeService runtimeService = processEngine.getRuntimeService();

    spanExporter.reset();

    ProcessInstance instance = runtimeService.startProcessInstanceByKey("testProcess");
    assertNotNull(instance, "Process instance should be created");

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertFalse(spans.isEmpty(), "At least one span should be recorded");

    List<String> spanNames = spans.stream()
        .map(SpanData::getName)
        .collect(Collectors.toList());

    assertTrue(
        spanNames.stream().anyMatch(name -> name.contains("Automated Task")),
        "Should have a span for the service task 'Automated Task', "
            + "but found spans: " + spanNames);
  }

  @Test
  @DisplayName("Span attributes contain correct BPMN metadata")
  void spanAttributesContainBpmnMetadata() {
    RuntimeService runtimeService = processEngine.getRuntimeService();

    spanExporter.reset();

    ProcessInstance instance = runtimeService.startProcessInstanceByKey("testProcess");

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertFalse(spans.isEmpty(), "Spans should be recorded");

    SpanData serviceTaskSpan = spans.stream()
        .filter(s -> "Automated Task".equals(s.getName()))
        .findFirst()
        .orElse(null);

    assertNotNull(serviceTaskSpan,
        "Should find a span named 'Automated Task', but found: "
            + spans.stream().map(SpanData::getName).collect(Collectors.toList()));

    AttributeKey<String> processDefKey =
        AttributeKey.stringKey(OpenTelemetryExecutionListener.ATTR_PROCESS_DEFINITION);
    AttributeKey<String> activityIdKey =
        AttributeKey.stringKey(OpenTelemetryExecutionListener.ATTR_ACTIVITY_ID);
    AttributeKey<String> instanceKey =
        AttributeKey.stringKey(OpenTelemetryExecutionListener.ATTR_PROCESS_INSTANCE);
    AttributeKey<String> outcomeKey =
        AttributeKey.stringKey(OpenTelemetryExecutionListener.ATTR_OUTCOME);

    String processDefinition = serviceTaskSpan.getAttributes().get(processDefKey);
    assertNotNull(processDefinition, "process definition attribute should be set");
    assertTrue(processDefinition.startsWith("testProcess"),
        "process definition should start with 'testProcess', was: " + processDefinition);

    assertEquals("serviceTask1", serviceTaskSpan.getAttributes().get(activityIdKey));
    assertEquals(instance.getProcessInstanceId(),
        serviceTaskSpan.getAttributes().get(instanceKey));
    assertEquals("completed", serviceTaskSpan.getAttributes().get(outcomeKey));
  }

  @Test
  @DisplayName("Metrics are recorded for task start and end events")
  void metricsAreRecordedForTaskEvents() throws InterruptedException {
    RuntimeService runtimeService = processEngine.getRuntimeService();

    metricExporter.reset();
    spanExporter.reset();

    runtimeService.startProcessInstanceByKey("testProcess");

    // Allow the periodic metric reader to flush
    Thread.sleep(500);

    Collection<MetricData> metrics = metricExporter.getFinishedMetricItems();
    assertFalse(metrics.isEmpty(), "At least one metric should be recorded");

    List<String> metricNames = metrics.stream()
        .map(MetricData::getName)
        .distinct()
        .collect(Collectors.toList());

    assertTrue(metricNames.contains("bpmn.task.started"),
        "Should record bpmn.task.started metric, but found: " + metricNames);
    assertTrue(metricNames.contains("bpmn.task.ended"),
        "Should record bpmn.task.ended metric, but found: " + metricNames);
  }

  @Test
  @DisplayName("Multiple process instances produce independent spans")
  void multipleInstancesProduceIndependentSpans() {
    RuntimeService runtimeService = processEngine.getRuntimeService();

    spanExporter.reset();

    ProcessInstance instance1 = runtimeService.startProcessInstanceByKey("testProcess");
    ProcessInstance instance2 = runtimeService.startProcessInstanceByKey("testProcess");

    List<SpanData> spans = spanExporter.getFinishedSpanItems();

    List<SpanData> serviceTaskSpans = spans.stream()
        .filter(s -> "Automated Task".equals(s.getName()))
        .collect(Collectors.toList());

    assertTrue(serviceTaskSpans.size() >= 2,
        "Should have at least 2 service task spans (one per instance), "
            + "but found " + serviceTaskSpans.size());

    AttributeKey<String> instanceKey =
        AttributeKey.stringKey(OpenTelemetryExecutionListener.ATTR_PROCESS_INSTANCE);

    List<String> instanceIds = serviceTaskSpans.stream()
        .map(s -> s.getAttributes().get(instanceKey))
        .distinct()
        .collect(Collectors.toList());

    assertTrue(instanceIds.size() >= 2,
        "Service task spans should reference distinct process instance IDs");
  }

  /**
   * Clears the static activeSpans and activeScopes maps in
   * {@link OpenTelemetryExecutionListener} via reflection to avoid cross-test leakage.
   */
  @SuppressWarnings("unchecked")
  private void clearStaticSpanMaps() throws Exception {
    Field spansField =
        OpenTelemetryExecutionListener.class.getDeclaredField("activeSpans");
    spansField.setAccessible(true);
    ((ConcurrentHashMap<?, ?>) spansField.get(null)).clear();

    Field scopesField =
        OpenTelemetryExecutionListener.class.getDeclaredField("activeScopes");
    scopesField.setAccessible(true);
    ((ConcurrentHashMap<?, ?>) scopesField.get(null)).clear();
  }
}
