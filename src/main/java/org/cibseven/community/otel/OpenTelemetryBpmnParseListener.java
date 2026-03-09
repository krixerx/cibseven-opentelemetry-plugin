package org.cibseven.community.otel;

import io.opentelemetry.api.OpenTelemetry;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.task.TaskDefinition;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hooks into the BPMN parse phase and attaches {@link OpenTelemetryExecutionListener} and {@link
 * OpenTelemetryTaskListener} to every activity in every process definition.
 *
 * <p>This listener fires once per process definition (at deploy time), not per instance — making it
 * very efficient.
 */
public class OpenTelemetryBpmnParseListener extends AbstractBpmnParseListener {

  private static final Logger log =
      LoggerFactory.getLogger(OpenTelemetryBpmnParseListener.class);

  private final OpenTelemetry openTelemetry;
  private final BpmnMetricsService metricsService;

  /** Creates a parse listener that wires OTel instrumentation into parsed BPMN definitions. */
  public OpenTelemetryBpmnParseListener(
      OpenTelemetry openTelemetry, BpmnMetricsService metricsService) {
    this.openTelemetry = openTelemetry;
    this.metricsService = metricsService;
  }

  // Process-level events

  @Override
  public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
    addExecutionListeners(processDefinition);
    log.debug("[CIB seven OTel] Instrumented process: {}", processDefinition.getKey());
  }

  // Task types

  @Override
  public void parseServiceTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseUserTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
    addTaskListeners(activity);
  }

  @Override
  public void parseScriptTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseSendTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseReceiveTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseBusinessRuleTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseCallActivity(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  // Gateway types

  @Override
  public void parseExclusiveGateway(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseInclusiveGateway(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseParallelGateway(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  // Start / End events

  @Override
  public void parseStartEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseEndEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseIntermediateThrowEvent(
      Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  @Override
  public void parseIntermediateCatchEvent(
      Element element, ScopeImpl scope, ActivityImpl activity) {
    addExecutionListeners(activity);
  }

  // Helpers

  private void addExecutionListeners(ActivityImpl activity) {
    OpenTelemetryExecutionListener listener =
        new OpenTelemetryExecutionListener(openTelemetry, metricsService);
    activity.addListener(ExecutionListener.EVENTNAME_START, listener);
    activity.addListener(ExecutionListener.EVENTNAME_END, listener);
  }

  private void addExecutionListeners(ProcessDefinitionEntity processDefinition) {
    OpenTelemetryExecutionListener listener =
        new OpenTelemetryExecutionListener(openTelemetry, metricsService);
    processDefinition.addListener(ExecutionListener.EVENTNAME_START, listener);
    processDefinition.addListener(ExecutionListener.EVENTNAME_END, listener);
  }

  private void addTaskListeners(ActivityImpl activity) {
    OpenTelemetryTaskListener taskListener =
        new OpenTelemetryTaskListener(openTelemetry, metricsService);
    TaskDefinition taskDefinition =
        (TaskDefinition) activity.getProperty("taskDefinition");
    if (taskDefinition != null) {
      taskDefinition.addTaskListener(TaskListener.EVENTNAME_CREATE, taskListener);
      taskDefinition.addTaskListener(TaskListener.EVENTNAME_COMPLETE, taskListener);
      taskDefinition.addTaskListener(TaskListener.EVENTNAME_DELETE, taskListener);
    }
  }
}
