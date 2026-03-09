package org.cibseven.community.otel;

import io.opentelemetry.api.OpenTelemetry;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CIB seven ProcessEnginePlugin that automatically instruments all BPMN process executions with
 * OpenTelemetry traces and metrics.
 *
 * <p>Simply add this plugin as a Spring bean (auto-configured) or declare it manually in your
 * ProcessEngineConfiguration. No changes to BPMN files needed.
 *
 * <p>Usage (Spring Boot — zero config needed, auto-detected):
 *
 * <pre>
 *   &lt;dependency&gt;
 *     &lt;groupId&gt;org.cibseven.community&lt;/groupId&gt;
 *     &lt;artifactId&gt;cibseven-opentelemetry-plugin&lt;/artifactId&gt;
 *   &lt;/dependency&gt;
 * </pre>
 */
@Component
public class OpenTelemetryProcessEnginePlugin extends AbstractProcessEnginePlugin {

  private static final Logger log =
      LoggerFactory.getLogger(OpenTelemetryProcessEnginePlugin.class);

  private final OpenTelemetry openTelemetry;
  private final BpmnMetricsService metricsService;

  /** Creates the plugin with the given OpenTelemetry instance and metrics service. */
  @Autowired
  public OpenTelemetryProcessEnginePlugin(
      OpenTelemetry openTelemetry, BpmnMetricsService metricsService) {
    this.openTelemetry = openTelemetry;
    this.metricsService = metricsService;
  }

  @Override
  public void preInit(ProcessEngineConfigurationImpl configuration) {
    log.info("[CIB seven OTel] Registering OpenTelemetry BPMN instrumentation plugin");

    List<BpmnParseListener> preParseListeners = configuration.getCustomPreBPMNParseListeners();
    if (preParseListeners == null) {
      preParseListeners = new ArrayList<>();
      configuration.setCustomPreBPMNParseListeners(preParseListeners);
    }
    preParseListeners.add(new OpenTelemetryBpmnParseListener(openTelemetry, metricsService));

    log.info(
        "[CIB seven OTel] Plugin registered. All BPMN processes will be instrumented.");
  }
}
