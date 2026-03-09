package org.cibseven.community.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Propagates OpenTelemetry trace context across async boundaries using CIB seven process
 * variables.
 *
 * <p>When a process hands off work asynchronously (e.g. to an external task worker via REST),
 * the trace context must be serialized and restored on the other side. This class stores W3C
 * Trace Context headers ({@code traceparent}, {@code tracestate}) as process variables so that
 * any participant can continue the same distributed trace.
 *
 * <p>Variable names: {@code _otel_traceparent} and {@code _otel_tracestate} (underscore prefix
 * indicates internal/infrastructure variables).
 */
public final class TraceContextPropagator {

  private static final Logger log = LoggerFactory.getLogger(TraceContextPropagator.class);

  /** Process variable name for the W3C traceparent header. */
  public static final String VAR_TRACEPARENT = "_otel_traceparent";

  /** Process variable name for the W3C tracestate header. */
  public static final String VAR_TRACESTATE = "_otel_tracestate";

  private TraceContextPropagator() {
    // Utility class — no instantiation
  }

  /**
   * Injects the current span's trace context into the given execution's process variables.
   *
   * <p>Call this before an async hand-off (e.g. before signalling an external task worker) so
   * the downstream participant can restore the trace context with
   * {@link #extractFromProcessVariables(DelegateExecution)}.
   *
   * @param execution the CIB seven execution whose variables will carry the trace context
   */
  public static void injectIntoProcessVariables(DelegateExecution execution) {
    Context current = Context.current();
    Span span = Span.fromContext(current);
    if (!span.getSpanContext().isValid()) {
      log.debug("[CIB seven OTel] No active span — skipping trace context injection");
      return;
    }

    Map<String, String> carrier = new HashMap<>();
    W3CTraceContextPropagator.getInstance()
        .inject(current, carrier, MapTextMapSetter.INSTANCE);

    String traceparent = carrier.get("traceparent");
    String tracestate = carrier.get("tracestate");

    if (traceparent != null) {
      execution.setVariable(VAR_TRACEPARENT, traceparent);
      log.debug("[CIB seven OTel] Injected traceparent: {}", traceparent);
    }
    if (tracestate != null && !tracestate.isEmpty()) {
      execution.setVariable(VAR_TRACESTATE, tracestate);
    }
  }

  /**
   * Extracts trace context from the given execution's process variables and returns an OTel
   * {@link Context} that can be used as a parent for new spans.
   *
   * <p>Usage example:
   * <pre>
   *   Context parentCtx = TraceContextPropagator.extractFromProcessVariables(execution);
   *   Span span = tracer.spanBuilder("my-task")
   *       .setParent(parentCtx)
   *       .startSpan();
   * </pre>
   *
   * @param execution the CIB seven execution containing trace context variables
   * @return the restored context, or {@link Context#current()} if no trace context is found
   */
  public static Context extractFromProcessVariables(DelegateExecution execution) {
    Object traceparentObj = execution.getVariable(VAR_TRACEPARENT);
    if (traceparentObj == null) {
      log.debug("[CIB seven OTel] No traceparent variable found — returning current context");
      return Context.current();
    }

    Map<String, String> carrier = new HashMap<>();
    carrier.put("traceparent", traceparentObj.toString());

    Object tracestateObj = execution.getVariable(VAR_TRACESTATE);
    if (tracestateObj != null) {
      carrier.put("tracestate", tracestateObj.toString());
    }

    Context extracted = W3CTraceContextPropagator.getInstance()
        .extract(Context.current(), carrier, MapTextMapGetter.INSTANCE);

    log.debug("[CIB seven OTel] Extracted trace context from process variables");
    return extracted;
  }

  /**
   * Convenience method to retrieve the stored traceparent header value.
   *
   * @param execution the CIB seven execution
   * @return the traceparent string, or {@code null} if not set
   */
  public static String getTraceParent(DelegateExecution execution) {
    Object value = execution.getVariable(VAR_TRACEPARENT);
    return value != null ? value.toString() : null;
  }

  /** {@link TextMapSetter} for {@code Map<String, String>} carriers. */
  private enum MapTextMapSetter implements TextMapSetter<Map<String, String>> {
    INSTANCE;

    @Override
    public void set(Map<String, String> carrier, String key, String value) {
      if (carrier != null) {
        carrier.put(key, value);
      }
    }
  }

  /** {@link TextMapGetter} for {@code Map<String, String>} carriers. */
  private enum MapTextMapGetter implements TextMapGetter<Map<String, String>> {
    INSTANCE;

    @Override
    public Iterable<String> keys(Map<String, String> carrier) {
      return carrier.keySet();
    }

    @Override
    public String get(Map<String, String> carrier, String key) {
      return carrier != null ? carrier.get(key) : null;
    }
  }
}
