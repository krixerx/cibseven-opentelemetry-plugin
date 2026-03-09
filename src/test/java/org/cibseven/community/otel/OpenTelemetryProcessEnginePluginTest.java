package org.cibseven.community.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link OpenTelemetryProcessEnginePlugin}.
 *
 * <p>Verifies that the plugin correctly registers its {@link OpenTelemetryBpmnParseListener}
 * in the process engine configuration during the {@code preInit} phase.
 */
class OpenTelemetryProcessEnginePluginTest {

  private OpenTelemetryProcessEnginePlugin plugin;
  private ProcessEngineConfigurationImpl configuration;

  @BeforeEach
  void setUp() {
    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder().build();
    BpmnMetricsService metricsService = mock(BpmnMetricsService.class);
    plugin = new OpenTelemetryProcessEnginePlugin(openTelemetry, metricsService);
    configuration = mock(ProcessEngineConfigurationImpl.class);
  }

  @Test
  @DisplayName("preInit creates a new list and registers parse listener when list is null")
  void preInitCreatesNewListWhenNull() {
    when(configuration.getCustomPreBPMNParseListeners()).thenReturn(null);

    plugin.preInit(configuration);

    // Verify setCustomPreBPMNParseListeners was called with a non-null list
    var captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(configuration).setCustomPreBPMNParseListeners(captor.capture());

    List<BpmnParseListener> listeners = captor.getValue();
    assertNotNull(listeners);
    assertEquals(1, listeners.size());
    assertInstanceOf(OpenTelemetryBpmnParseListener.class, listeners.get(0));
  }

  @Test
  @DisplayName("preInit appends to existing list when list already exists")
  void preInitAppendsToExistingList() {
    List<BpmnParseListener> existingListeners = new ArrayList<>();
    BpmnParseListener existingListener = mock(BpmnParseListener.class);
    existingListeners.add(existingListener);

    when(configuration.getCustomPreBPMNParseListeners()).thenReturn(existingListeners);

    plugin.preInit(configuration);

    assertEquals(2, existingListeners.size());
    assertInstanceOf(OpenTelemetryBpmnParseListener.class, existingListeners.get(1));
    // Original listener should still be at index 0
    assertEquals(existingListener, existingListeners.get(0));
  }

  @Test
  @DisplayName("preInit registers exactly one OpenTelemetryBpmnParseListener")
  void preInitRegistersExactlyOneListener() {
    List<BpmnParseListener> listeners = new ArrayList<>();
    when(configuration.getCustomPreBPMNParseListeners()).thenReturn(listeners);

    plugin.preInit(configuration);

    assertEquals(1, listeners.size());
    assertInstanceOf(OpenTelemetryBpmnParseListener.class, listeners.get(0));
  }
}
