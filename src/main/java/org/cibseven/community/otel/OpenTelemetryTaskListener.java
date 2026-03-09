package org.cibseven.community.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.cibseven.bpm.engine.delegate.DelegateTask;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates OpenTelemetry spans for the full lifecycle of user tasks: CREATE -> (human works) ->
 * COMPLETE or DELETE (rejected/cancelled).
 *
 * <p>User task spans can last minutes, hours, or days — making them especially valuable for SLA
 * monitoring and bottleneck detection.
 */
public class OpenTelemetryTaskListener implements TaskListener {

  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryTaskListener.class);

  /** Span attribute: user task ID. */
  public static final String ATTR_TASK_ID = "bpmn.task.id";

  /** Span attribute: user task name. */
  public static final String ATTR_TASK_NAME = "bpmn.task.name";

  /** Span attribute: user task assignee. */
  public static final String ATTR_TASK_ASSIGNEE = "bpmn.task.assignee";

  /** Span attribute: user task outcome (e.g. completed, cancelled, rejected). */
  public static final String ATTR_TASK_OUTCOME = "bpmn.task.outcome";

  /** Span attribute: BPMN process name. */
  public static final String ATTR_PROCESS_NAME = "bpmn.process.name";

  /** Span attribute: process instance ID. */
  public static final String ATTR_INSTANCE_ID = "bpmn.process.instance";

  private final Tracer tracer;
  private final BpmnMetricsService metricsService;

  private static final ConcurrentHashMap<String, Span> activeSpans = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Scope> activeScopes = new ConcurrentHashMap<>();

  /** Creates a task listener that records OTel spans and metrics per user task. */
  public OpenTelemetryTaskListener(
      OpenTelemetry openTelemetry, BpmnMetricsService metricsService) {
    this.tracer = openTelemetry.getTracer("cibseven-bpmn-tasks", "1.0.0");
    this.metricsService = metricsService;
  }

  @Override
  public void notify(DelegateTask task) {
    String event = task.getEventName();
    String taskId = task.getId();
    String taskName = task.getName() != null ? task.getName() : task.getTaskDefinitionKey();
    String instanceId = task.getProcessInstanceId();
    String processName = extractProcessName(task.getProcessDefinitionId());

    switch (event) {
      case EVENTNAME_CREATE -> onCreate(taskId, taskName, instanceId, processName);
      case EVENTNAME_COMPLETE -> onComplete(taskId, taskName, instanceId, processName);
      case EVENTNAME_DELETE -> onDelete(taskId, taskName, instanceId, processName, task);
      default -> log.debug("[CIB seven OTel] Unhandled task event: {}", event);
    }
  }

  private void onCreate(
      String taskId, String taskName, String instanceId, String processName) {
    try {
      Span span = tracer.spanBuilder("UserTask: " + taskName)
          .setAttribute(ATTR_TASK_ID, taskId)
          .setAttribute(ATTR_TASK_NAME, taskName)
          .setAttribute(ATTR_PROCESS_NAME, processName)
          .setAttribute(ATTR_INSTANCE_ID, instanceId)
          .startSpan();

      Scope scope = span.makeCurrent();
      activeSpans.put(taskId, span);
      activeScopes.put(taskId, scope);

      metricsService.onTaskStart(processName, taskName, instanceId);
      log.debug("[CIB seven OTel] User task span created: {} ({})", taskName, taskId);
    } catch (Exception e) {
      log.warn("[CIB seven OTel] Failed to create task span {}: {}", taskId, e.getMessage());
    }
  }

  private void onComplete(
      String taskId, String taskName, String instanceId, String processName) {
    finishSpan(taskId, taskName, instanceId, processName, "completed", StatusCode.OK);
  }

  private void onDelete(String taskId, String taskName, String instanceId, String processName,
      DelegateTask task) {
    Object outcomeVar = task.getVariable("taskOutcome");
    String outcome = (outcomeVar != null) ? outcomeVar.toString() : "cancelled";
    StatusCode status = "rejected".equals(outcome) ? StatusCode.ERROR : StatusCode.OK;
    finishSpan(taskId, taskName, instanceId, processName, outcome, status);
  }

  private void finishSpan(String taskId, String taskName, String instanceId, String processName,
      String outcome, StatusCode statusCode) {
    try {
      Scope scope = activeScopes.remove(taskId);
      Span span = activeSpans.remove(taskId);

      if (span != null) {
        span.setAttribute(ATTR_TASK_OUTCOME, outcome);
        span.setStatus(statusCode);
        if (scope != null) {
          scope.close();
        }
        span.end();
        metricsService.onTaskEnd(processName, taskName, instanceId, outcome);
        log.debug("[CIB seven OTel] User task span finished: {} outcome={}", taskName, outcome);
      }
    } catch (Exception e) {
      log.warn("[CIB seven OTel] Failed to finish task span {}: {}", taskId, e.getMessage());
    }
  }

  private String extractProcessName(String processDefinitionId) {
    if (processDefinitionId == null) {
      return "unknown";
    }
    return processDefinitionId.contains(":")
        ? processDefinitionId.split(":")[0]
        : processDefinitionId;
  }
}
