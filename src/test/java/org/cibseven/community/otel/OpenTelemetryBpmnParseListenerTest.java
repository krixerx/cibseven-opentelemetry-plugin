package org.cibseven.community.otel;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.task.TaskDefinition;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenTelemetryBpmnParseListener}.
 *
 * <p>Verifies that the parse listener correctly attaches execution and task listeners
 * to the appropriate BPMN elements during the parse phase.
 */
class OpenTelemetryBpmnParseListenerTest {

  private OpenTelemetryBpmnParseListener parseListener;
  private Element element;
  private ScopeImpl scope;

  @BeforeEach
  void setUp() {
    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder().build();
    BpmnMetricsService metricsService = mock(BpmnMetricsService.class);
    parseListener = new OpenTelemetryBpmnParseListener(openTelemetry, metricsService);

    element = mock(Element.class);
    scope = mock(ScopeImpl.class);
  }

  // Process-level

  @Test
  @DisplayName("parseProcess adds START and END execution listeners to ProcessDefinitionEntity")
  void parseProcessAddsExecutionListeners() {
    ProcessDefinitionEntity processDefinition = mock(ProcessDefinitionEntity.class);
    when(processDefinition.getKey()).thenReturn("testProcess");

    parseListener.parseProcess(element, processDefinition);

    verify(processDefinition).addListener(
        eq(ExecutionListener.EVENTNAME_START), any(OpenTelemetryExecutionListener.class));
    verify(processDefinition).addListener(
        eq(ExecutionListener.EVENTNAME_END), any(OpenTelemetryExecutionListener.class));
  }

  // Task types

  @Test
  @DisplayName("parseServiceTask adds START and END execution listeners to ActivityImpl")
  void parseServiceTaskAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);

    parseListener.parseServiceTask(element, scope, activity);

    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseUserTask adds execution listeners and task listeners")
  void parseUserTaskAddsExecutionAndTaskListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    TaskDefinition taskDefinition = mock(TaskDefinition.class);
    when(activity.getProperty("taskDefinition")).thenReturn(taskDefinition);

    parseListener.parseUserTask(element, scope, activity);

    verifyExecutionListenersAdded(activity);
    verify(taskDefinition).addTaskListener(
        eq(TaskListener.EVENTNAME_CREATE), any(OpenTelemetryTaskListener.class));
    verify(taskDefinition).addTaskListener(
        eq(TaskListener.EVENTNAME_COMPLETE), any(OpenTelemetryTaskListener.class));
    verify(taskDefinition).addTaskListener(
        eq(TaskListener.EVENTNAME_DELETE), any(OpenTelemetryTaskListener.class));
  }

  @Test
  @DisplayName("parseUserTask with null taskDefinition does not add task listeners")
  void parseUserTaskNullTaskDefinitionDoesNotCrash() {
    ActivityImpl activity = mock(ActivityImpl.class);
    when(activity.getProperty("taskDefinition")).thenReturn(null);

    parseListener.parseUserTask(element, scope, activity);

    // Execution listeners should still be added
    verifyExecutionListenersAdded(activity);
    // No NPE should occur — this is the key assertion (test passes if no exception)
  }

  @Test
  @DisplayName("parseScriptTask adds execution listeners")
  void parseScriptTaskAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseScriptTask(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseSendTask adds execution listeners")
  void parseSendTaskAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseSendTask(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseReceiveTask adds execution listeners")
  void parseReceiveTaskAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseReceiveTask(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseBusinessRuleTask adds execution listeners")
  void parseBusinessRuleTaskAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseBusinessRuleTask(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseCallActivity adds execution listeners")
  void parseCallActivityAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseCallActivity(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  // Gateway types

  @Test
  @DisplayName("parseExclusiveGateway adds execution listeners")
  void parseExclusiveGatewayAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseExclusiveGateway(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseInclusiveGateway adds execution listeners")
  void parseInclusiveGatewayAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseInclusiveGateway(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseParallelGateway adds execution listeners")
  void parseParallelGatewayAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseParallelGateway(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  // Event types

  @Test
  @DisplayName("parseStartEvent adds execution listeners")
  void parseStartEventAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseStartEvent(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseEndEvent adds execution listeners")
  void parseEndEventAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseEndEvent(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseIntermediateThrowEvent adds execution listeners")
  void parseIntermediateThrowEventAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseIntermediateThrowEvent(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  @Test
  @DisplayName("parseIntermediateCatchEvent adds execution listeners")
  void parseIntermediateCatchEventAddsExecutionListeners() {
    ActivityImpl activity = mock(ActivityImpl.class);
    parseListener.parseIntermediateCatchEvent(element, scope, activity);
    verifyExecutionListenersAdded(activity);
  }

  /**
   * Verifies that both START and END execution listeners were added to the activity.
   */
  private void verifyExecutionListenersAdded(ActivityImpl activity) {
    verify(activity).addListener(
        eq(ExecutionListener.EVENTNAME_START),
        any(OpenTelemetryExecutionListener.class));
    verify(activity).addListener(
        eq(ExecutionListener.EVENTNAME_END),
        any(OpenTelemetryExecutionListener.class));
  }
}
