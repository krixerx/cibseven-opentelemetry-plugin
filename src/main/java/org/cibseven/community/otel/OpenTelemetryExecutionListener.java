package org.cibseven.community.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and manages OpenTelemetry spans for each BPMN activity execution.
 *
 * <p>A span is started when an activity starts ({@code EVENTNAME_START}) and ended when it
 * completes ({@code EVENTNAME_END}). The span carries BPMN-specific attributes so it can be
 * filtered and grouped in Jaeger / Grafana Tempo.
 *
 * <p>Span key format: {@code processInstanceId:activityId}
 */
public class OpenTelemetryExecutionListener implements ExecutionListener {

  private static final Logger log =
      LoggerFactory.getLogger(OpenTelemetryExecutionListener.class);

  /** Span attribute: BPMN process definition ID. */
  public static final String ATTR_PROCESS_DEFINITION = "bpmn.process.definition";

  /** Span attribute: process instance ID. */
  public static final String ATTR_PROCESS_INSTANCE = "bpmn.process.instance";

  /** Span attribute: BPMN activity ID. */
  public static final String ATTR_ACTIVITY_ID = "bpmn.activity.id";

  /** Span attribute: BPMN activity name. */
  public static final String ATTR_ACTIVITY_NAME = "bpmn.activity.name";

  /** Span attribute: BPMN activity type (e.g. serviceTask, userTask). */
  public static final String ATTR_ACTIVITY_TYPE = "bpmn.activity.type";

  /** Span attribute: execution outcome (e.g. completed, failed). */
  public static final String ATTR_OUTCOME = "bpmn.outcome";

  private final Tracer tracer;
  private final BpmnMetricsService metricsService;

  private static final ConcurrentHashMap<String, Span> activeSpans = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Scope> activeScopes = new ConcurrentHashMap<>();

  /** Creates an execution listener that records OTel spans and metrics per activity. */
  public OpenTelemetryExecutionListener(
      OpenTelemetry openTelemetry, BpmnMetricsService metricsService) {
    this.tracer = openTelemetry.getTracer("cibseven-bpmn", "1.0.0");
    this.metricsService = metricsService;
  }

  @Override
  public void notify(DelegateExecution execution) {
    String event = execution.getEventName();
    String instanceId = execution.getProcessInstanceId();
    String activityId = execution.getCurrentActivityId();
    String processDefId = execution.getProcessDefinitionId();
    String processName = extractProcessName(processDefId);
    String activityName = execution.getCurrentActivityName();
    String displayName =
        (activityName != null && !activityName.isBlank()) ? activityName : activityId;

    String spanKey = instanceId + ":" + activityId;

    if (EVENTNAME_START.equals(event)) {
      onStart(spanKey, displayName, processDefId, processName, instanceId, activityId,
          activityName, execution);
    } else if (EVENTNAME_END.equals(event)) {
      onEnd(spanKey, processName, displayName, instanceId);
    }
  }

  private void onStart(String spanKey, String displayName, String processDefId,
      String processName, String instanceId, String activityId, String activityName,
      DelegateExecution execution) {
    try {
      String activityType = resolveActivityType(execution);

      Span span = tracer.spanBuilder(displayName)
          .setAttribute(ATTR_PROCESS_DEFINITION, processDefId)
          .setAttribute(ATTR_PROCESS_INSTANCE, instanceId)
          .setAttribute(ATTR_ACTIVITY_ID, activityId != null ? activityId : "")
          .setAttribute(ATTR_ACTIVITY_NAME, activityName != null ? activityName : "")
          .setAttribute(ATTR_ACTIVITY_TYPE, activityType)
          .startSpan();

      Scope scope = span.makeCurrent();
      activeSpans.put(spanKey, span);
      activeScopes.put(spanKey, scope);

      metricsService.onTaskStart(processName, displayName, instanceId);
      log.debug("[CIB seven OTel] Span started: {} ({})", displayName, spanKey);
    } catch (Exception e) {
      log.warn("[CIB seven OTel] Failed to start span for {}: {}", spanKey, e.getMessage());
    }
  }

  private void onEnd(String spanKey, String processName, String displayName, String instanceId) {
    try {
      Scope scope = activeScopes.remove(spanKey);
      Span span = activeSpans.remove(spanKey);

      if (span != null) {
        span.setAttribute(ATTR_OUTCOME, "completed");
        span.setStatus(StatusCode.OK);
        if (scope != null) {
          scope.close();
        }
        span.end();
        metricsService.onTaskEnd(processName, displayName, instanceId, "completed");
        log.debug("[CIB seven OTel] Span ended: {} ({})", displayName, spanKey);
      }
    } catch (Exception e) {
      log.warn("[CIB seven OTel] Failed to end span for {}: {}", spanKey, e.getMessage());
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

  private String resolveActivityType(DelegateExecution execution) {
    try {
      return execution.getBpmnModelElementInstance()
          .getElementType()
          .getTypeName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
