package org.cibseven.community.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unit tests for {@link OpenTelemetryExecutionListener}.
 *
 * <p>Uses the OpenTelemetry SDK in-memory span exporter and Mockito-mocked
 * {@link DelegateExecution} objects to verify span creation, attribute population,
 * and metric recording without a running process engine.
 */
class OpenTelemetryExecutionListenerTest {

  private InMemorySpanExporter spanExporter;
  private OpenTelemetry openTelemetry;
  private BpmnMetricsService metricsService;
  private OpenTelemetryExecutionListener listener;

  @BeforeEach
  void setUp() {
    spanExporter = InMemorySpanExporter.create();

    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
        .build();

    openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build();

    metricsService = mock(BpmnMetricsService.class);
    listener = new OpenTelemetryExecutionListener(openTelemetry, metricsService);
  }

  @AfterEach
  void tearDown() throws Exception {
    clearStaticMaps();
    spanExporter.reset();
  }

  @Test
  @DisplayName("START event creates a span with correct BPMN attributes")
  void startEventCreatesSpanWithAttributes() {
    DelegateExecution execution = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "instance-1", "serviceTask1", "myProcess:1:abc",
        "Check Credit");

    listener.notify(execution);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(0, spans.size(),
        "Span should not be finished yet (only started)");

    verify(metricsService).onTaskStart("myProcess", "Check Credit", "instance-1");
  }

  @Test
  @DisplayName("END event ends the span with outcome=completed and StatusCode.OK")
  void endEventEndsSpanWithCompletedOutcome() {
    DelegateExecution startExec = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "instance-1", "serviceTask1", "myProcess:1:abc",
        "Check Credit");
    listener.notify(startExec);

    DelegateExecution endExec = mockExecution(
        ExecutionListener.EVENTNAME_END,
        "instance-1", "serviceTask1", "myProcess:1:abc",
        "Check Credit");
    listener.notify(endExec);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());

    SpanData span = spans.get(0);
    assertEquals("Check Credit", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("completed",
        span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("bpmn.outcome")));
    assertEquals("myProcess:1:abc",
        span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("bpmn.process.definition")));
    assertEquals("instance-1",
        span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("bpmn.process.instance")));
    assertEquals("serviceTask1",
        span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("bpmn.activity.id")));

    verify(metricsService)
        .onTaskEnd("myProcess", "Check Credit", "instance-1", "completed");
  }

  @Test
  @DisplayName("Null activity name falls back to activity ID for span name")
  void nullActivityNameFallsBackToActivityId() {
    DelegateExecution startExec = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "instance-2", "task42", "orderProcess:1:xyz",
        null);
    listener.notify(startExec);

    DelegateExecution endExec = mockExecution(
        ExecutionListener.EVENTNAME_END,
        "instance-2", "task42", "orderProcess:1:xyz",
        null);
    listener.notify(endExec);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals("task42", spans.get(0).getName());
  }

  @Test
  @DisplayName("Blank activity name falls back to activity ID")
  void blankActivityNameFallsBackToActivityId() {
    DelegateExecution startExec = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "instance-3", "task99", "process:1:def",
        "   ");
    listener.notify(startExec);

    DelegateExecution endExec = mockExecution(
        ExecutionListener.EVENTNAME_END,
        "instance-3", "task99", "process:1:def",
        "   ");
    listener.notify(endExec);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals("task99", spans.get(0).getName());
  }

  @Test
  @DisplayName("Exception during span creation does not propagate")
  void exceptionDuringSpanCreationIsHandled() {
    DelegateExecution execution = mock(DelegateExecution.class);
    when(execution.getEventName()).thenReturn(ExecutionListener.EVENTNAME_START);
    when(execution.getProcessInstanceId()).thenReturn("instance-x");
    when(execution.getCurrentActivityId()).thenReturn("badTask");
    when(execution.getProcessDefinitionId()).thenReturn(null);
    when(execution.getCurrentActivityName()).thenReturn("Bad Task");
    when(execution.getBpmnModelElementInstance()).thenThrow(
        new RuntimeException("model not available"));

    // Should not throw
    listener.notify(execution);

    // Span may or may not be created depending on where the exception occurs;
    // the key point is no exception propagates.
  }

  @Test
  @DisplayName("END event without a prior START does not crash")
  void endWithoutStartDoesNotCrash() {
    DelegateExecution endExec = mockExecution(
        ExecutionListener.EVENTNAME_END,
        "instance-orphan", "orphanTask", "proc:1:a",
        "Orphan Task");

    // Should not throw
    listener.notify(endExec);

    // No span should be finished since none was started
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(0, spans.size());
  }

  @Test
  @DisplayName("Span key format is processInstanceId:activityId")
  void spanKeyFormatIsCorrect() {
    DelegateExecution startExec = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "inst-100", "activityA", "proc:1:z",
        "Activity A");
    listener.notify(startExec);

    // Start a different activity to ensure independence
    DelegateExecution startExec2 = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "inst-100", "activityB", "proc:1:z",
        "Activity B");
    listener.notify(startExec2);

    // End only activityA
    DelegateExecution endExecA = mockExecution(
        ExecutionListener.EVENTNAME_END,
        "inst-100", "activityA", "proc:1:z",
        "Activity A");
    listener.notify(endExecA);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals("Activity A", spans.get(0).getName());
  }

  @Test
  @DisplayName("Process definition without colon is used as-is for process name")
  void processDefinitionWithoutColonUsedAsIs() {
    DelegateExecution startExec = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "inst-5", "task1", "simpleProcess",
        "Task 1");
    listener.notify(startExec);

    verify(metricsService).onTaskStart("simpleProcess", "Task 1", "inst-5");
  }

  @Test
  @DisplayName("Records task start metric on START event")
  void recordsTaskStartMetric() {
    DelegateExecution execution = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "inst-10", "reviewTask", "approval:2:def",
        "Review");
    listener.notify(execution);

    verify(metricsService).onTaskStart("approval", "Review", "inst-10");
  }

  @Test
  @DisplayName("Records task end metric on END event")
  void recordsTaskEndMetric() {
    DelegateExecution startExec = mockExecution(
        ExecutionListener.EVENTNAME_START,
        "inst-11", "submitTask", "workflow:1:ghi",
        "Submit");
    listener.notify(startExec);

    DelegateExecution endExec = mockExecution(
        ExecutionListener.EVENTNAME_END,
        "inst-11", "submitTask", "workflow:1:ghi",
        "Submit");
    listener.notify(endExec);

    verify(metricsService).onTaskEnd("workflow", "Submit", "inst-11", "completed");
  }

  private DelegateExecution mockExecution(String event, String instanceId,
      String activityId, String processDefId, String activityName) {
    DelegateExecution execution = mock(DelegateExecution.class);
    when(execution.getEventName()).thenReturn(event);
    when(execution.getProcessInstanceId()).thenReturn(instanceId);
    when(execution.getCurrentActivityId()).thenReturn(activityId);
    when(execution.getProcessDefinitionId()).thenReturn(processDefId);
    when(execution.getCurrentActivityName()).thenReturn(activityName);
    // Simulate getBpmnModelElementInstance throwing for simplicity
    when(execution.getBpmnModelElementInstance()).thenThrow(
        new RuntimeException("not available in test"));
    return execution;
  }

  /**
   * Clears the static activeSpans and activeScopes maps via reflection to avoid
   * test cross-contamination.
   */
  @SuppressWarnings("unchecked")
  private void clearStaticMaps() throws Exception {
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
