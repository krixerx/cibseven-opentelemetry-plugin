package org.cibseven.community.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link TraceContextPropagator}.
 *
 * <p>Verifies W3C trace context injection/extraction via CIB seven process variables.
 */
class TraceContextPropagatorTest {

  private DelegateExecution execution;
  private OpenTelemetrySdk openTelemetry;

  @BeforeEach
  void setUp() {
    execution = mock(DelegateExecution.class);

    InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(
            io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(spanExporter))
        .build();

    openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build();
  }

  @Test
  @DisplayName("inject stores traceparent in process variables when span is active")
  void testInjectWithActiveSpan() {
    Span span = openTelemetry.getTracer("test")
        .spanBuilder("test-span")
        .startSpan();
    try (Scope ignored = span.makeCurrent()) {
      TraceContextPropagator.injectIntoProcessVariables(execution);

      ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
      verify(execution).setVariable(
          eq(TraceContextPropagator.VAR_TRACEPARENT), valueCaptor.capture());

      String traceparent = valueCaptor.getValue();
      assertNotNull(traceparent);
      assertTrue(traceparent.startsWith("00-"),
          "traceparent should start with version '00-'");
    } finally {
      span.end();
    }
  }

  @Test
  @DisplayName("inject does nothing when no active span")
  void testInjectWithoutActiveSpan() {
    // Explicitly clear any lingering span context from other tests
    try (Scope ignored = Context.root().makeCurrent()) {
      TraceContextPropagator.injectIntoProcessVariables(execution);

      verify(execution, never()).setVariable(
          eq(TraceContextPropagator.VAR_TRACEPARENT),
          org.mockito.ArgumentMatchers.any());
    }
  }

  @Test
  @DisplayName("round-trip: inject then extract preserves trace and span IDs")
  void testRoundTrip() {
    Span span = openTelemetry.getTracer("test")
        .spanBuilder("round-trip-span")
        .startSpan();

    String expectedTraceId = span.getSpanContext().getTraceId();
    String expectedSpanId = span.getSpanContext().getSpanId();

    // Capture the traceparent set on the mock
    ArgumentCaptor<String> traceparentCaptor = ArgumentCaptor.forClass(String.class);

    try (Scope ignored = span.makeCurrent()) {
      TraceContextPropagator.injectIntoProcessVariables(execution);
    } finally {
      span.end();
    }

    verify(execution).setVariable(
        eq(TraceContextPropagator.VAR_TRACEPARENT), traceparentCaptor.capture());

    String traceparent = traceparentCaptor.getValue();

    // Now set up mock to return the captured traceparent
    when(execution.getVariable(TraceContextPropagator.VAR_TRACEPARENT))
        .thenReturn(traceparent);
    when(execution.getVariable(TraceContextPropagator.VAR_TRACESTATE))
        .thenReturn(null);

    Context extracted = TraceContextPropagator.extractFromProcessVariables(execution);
    Span extractedSpan = Span.fromContext(extracted);

    assertEquals(expectedTraceId, extractedSpan.getSpanContext().getTraceId(),
        "Trace ID should survive round-trip");
    assertEquals(expectedSpanId, extractedSpan.getSpanContext().getSpanId(),
        "Span ID should survive round-trip");
  }

  @Test
  @DisplayName("extract returns context with invalid span when no variables are set")
  void testExtractWithNoVariables() {
    when(execution.getVariable(TraceContextPropagator.VAR_TRACEPARENT)).thenReturn(null);

    Context result = TraceContextPropagator.extractFromProcessVariables(execution);
    Span span = Span.fromContext(result);

    assertTrue(!span.getSpanContext().isValid() || span.getSpanContext().equals(
        Span.current().getSpanContext()),
        "Should return current context when no traceparent is stored");
  }

  @Test
  @DisplayName("getTraceParent returns stored value")
  void testGetTraceParent() {
    String expected = "00-abcdef1234567890abcdef1234567890-1234567890abcdef-01";
    when(execution.getVariable(TraceContextPropagator.VAR_TRACEPARENT)).thenReturn(expected);

    assertEquals(expected, TraceContextPropagator.getTraceParent(execution));
  }

  @Test
  @DisplayName("getTraceParent returns null when no variable is set")
  void testGetTraceParentNull() {
    when(execution.getVariable(TraceContextPropagator.VAR_TRACEPARENT)).thenReturn(null);

    assertNull(TraceContextPropagator.getTraceParent(execution));
  }
}
