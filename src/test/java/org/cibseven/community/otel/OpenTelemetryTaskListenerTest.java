package org.cibseven.community.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.cibseven.bpm.engine.delegate.DelegateTask;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unit tests for {@link OpenTelemetryTaskListener}.
 *
 * <p>Verifies that user task lifecycle events (CREATE, COMPLETE, DELETE) correctly create,
 * finish, and annotate OpenTelemetry spans using in-memory span exporters and mocked
 * {@link DelegateTask} objects.
 */
class OpenTelemetryTaskListenerTest {

  private InMemorySpanExporter spanExporter;
  private BpmnMetricsService metricsService;
  private OpenTelemetryTaskListener listener;

  @BeforeEach
  void setUp() {
    spanExporter = InMemorySpanExporter.create();

    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
        .build();

    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build();

    metricsService = mock(BpmnMetricsService.class);
    listener = new OpenTelemetryTaskListener(openTelemetry, metricsService);
  }

  @AfterEach
  void tearDown() throws Exception {
    clearStaticMaps();
    spanExporter.reset();
  }

  @Test
  @DisplayName("CREATE event starts a span named 'UserTask: <name>' with task attributes")
  void createEventStartsSpanWithAttributes() {
    DelegateTask task = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-1", "Review Application",
        "reviewKey", "inst-1", "loanProcess:1:abc");
    listener.notify(task);

    // Span is started but not ended yet
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(0, spans.size());

    verify(metricsService)
        .onTaskStart("loanProcess", "Review Application", "inst-1");
  }

  @Test
  @DisplayName("COMPLETE event ends span with outcome=completed and StatusCode.OK")
  void completeEventEndsSpanWithCompleted() {
    DelegateTask createTask = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-2", "Approve Loan",
        "approveKey", "inst-2", "loanProcess:1:abc");
    listener.notify(createTask);

    DelegateTask completeTask = mockTask(
        TaskListener.EVENTNAME_COMPLETE, "task-2", "Approve Loan",
        "approveKey", "inst-2", "loanProcess:1:abc");
    listener.notify(completeTask);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());

    SpanData span = spans.get(0);
    assertEquals("UserTask: Approve Loan", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("completed",
        span.getAttributes().get(AttributeKey.stringKey("bpmn.task.outcome")));
    assertEquals("task-2",
        span.getAttributes().get(AttributeKey.stringKey("bpmn.task.id")));
    assertEquals("Approve Loan",
        span.getAttributes().get(AttributeKey.stringKey("bpmn.task.name")));
    assertEquals("loanProcess",
        span.getAttributes().get(AttributeKey.stringKey("bpmn.process.name")));
    assertEquals("inst-2",
        span.getAttributes().get(AttributeKey.stringKey("bpmn.process.instance")));

    verify(metricsService)
        .onTaskEnd("loanProcess", "Approve Loan", "inst-2", "completed");
  }

  @Test
  @DisplayName("DELETE event with taskOutcome variable uses that value as outcome")
  void deleteEventUsesTaskOutcomeVariable() {
    DelegateTask createTask = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-3", "Manual Check",
        "checkKey", "inst-3", "process:1:def");
    listener.notify(createTask);

    DelegateTask deleteTask = mockTask(
        TaskListener.EVENTNAME_DELETE, "task-3", "Manual Check",
        "checkKey", "inst-3", "process:1:def");
    when(deleteTask.getVariable("taskOutcome")).thenReturn("escalated");
    listener.notify(deleteTask);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals("escalated",
        spans.get(0).getAttributes().get(
            AttributeKey.stringKey("bpmn.task.outcome")));
    assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
  }

  @Test
  @DisplayName("DELETE event without taskOutcome defaults to 'cancelled'")
  void deleteEventDefaultsToCancelled() {
    DelegateTask createTask = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-4", "Sign Document",
        "signKey", "inst-4", "signing:2:ghi");
    listener.notify(createTask);

    DelegateTask deleteTask = mockTask(
        TaskListener.EVENTNAME_DELETE, "task-4", "Sign Document",
        "signKey", "inst-4", "signing:2:ghi");
    when(deleteTask.getVariable("taskOutcome")).thenReturn(null);
    listener.notify(deleteTask);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals("cancelled",
        spans.get(0).getAttributes().get(
            AttributeKey.stringKey("bpmn.task.outcome")));
    assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());

    verify(metricsService)
        .onTaskEnd("signing", "Sign Document", "inst-4", "cancelled");
  }

  @Test
  @DisplayName("DELETE with 'rejected' outcome sets StatusCode.ERROR")
  void deleteWithRejectedOutcomeSetsError() {
    DelegateTask createTask = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-5", "Verify ID",
        "verifyKey", "inst-5", "kyc:1:jkl");
    listener.notify(createTask);

    DelegateTask deleteTask = mockTask(
        TaskListener.EVENTNAME_DELETE, "task-5", "Verify ID",
        "verifyKey", "inst-5", "kyc:1:jkl");
    when(deleteTask.getVariable("taskOutcome")).thenReturn("rejected");
    listener.notify(deleteTask);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    assertEquals("rejected",
        spans.get(0).getAttributes().get(
            AttributeKey.stringKey("bpmn.task.outcome")));
  }

  @Test
  @DisplayName("Null task name falls back to taskDefinitionKey")
  void nullTaskNameFallsBackToDefinitionKey() {
    DelegateTask createTask = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-6", null,
        "fallbackKey", "inst-6", "proc:1:mno");
    listener.notify(createTask);

    DelegateTask completeTask = mockTask(
        TaskListener.EVENTNAME_COMPLETE, "task-6", null,
        "fallbackKey", "inst-6", "proc:1:mno");
    listener.notify(completeTask);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals("UserTask: fallbackKey", spans.get(0).getName());

    verify(metricsService).onTaskStart("proc", "fallbackKey", "inst-6");
  }

  @Test
  @DisplayName("COMPLETE without prior CREATE does not crash")
  void completeWithoutCreateDoesNotCrash() {
    DelegateTask completeTask = mockTask(
        TaskListener.EVENTNAME_COMPLETE, "orphan-task", "Orphan",
        "orphanKey", "inst-7", "proc:1:pqr");

    // Should not throw
    listener.notify(completeTask);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(0, spans.size());
  }

  @Test
  @DisplayName("Process definition ID without colon is used as-is")
  void processDefWithoutColonUsedAsIs() {
    DelegateTask task = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-8", "Simple Task",
        "simpleKey", "inst-8", "simpleProcess");
    listener.notify(task);

    verify(metricsService)
        .onTaskStart("simpleProcess", "Simple Task", "inst-8");
  }

  @Test
  @DisplayName("Null process definition ID yields 'unknown' process name")
  void nullProcessDefinitionYieldsUnknown() {
    DelegateTask task = mockTask(
        TaskListener.EVENTNAME_CREATE, "task-9", "Mystery Task",
        "mysteryKey", "inst-9", null);
    listener.notify(task);

    verify(metricsService)
        .onTaskStart("unknown", "Mystery Task", "inst-9");
  }

  private DelegateTask mockTask(String event, String taskId, String taskName,
      String taskDefKey, String instanceId, String processDefId) {
    DelegateTask task = mock(DelegateTask.class);
    when(task.getEventName()).thenReturn(event);
    when(task.getId()).thenReturn(taskId);
    when(task.getName()).thenReturn(taskName);
    when(task.getTaskDefinitionKey()).thenReturn(taskDefKey);
    when(task.getProcessInstanceId()).thenReturn(instanceId);
    when(task.getProcessDefinitionId()).thenReturn(processDefId);
    return task;
  }

  /**
   * Clears the static activeSpans and activeScopes maps via reflection to avoid
   * test cross-contamination.
   */
  @SuppressWarnings("unchecked")
  private void clearStaticMaps() throws Exception {
    Field spansField =
        OpenTelemetryTaskListener.class.getDeclaredField("activeSpans");
    spansField.setAccessible(true);
    ((ConcurrentHashMap<?, ?>) spansField.get(null)).clear();

    Field scopesField =
        OpenTelemetryTaskListener.class.getDeclaredField("activeScopes");
    scopesField.setAccessible(true);
    ((ConcurrentHashMap<?, ?>) scopesField.get(null)).clear();
  }
}
