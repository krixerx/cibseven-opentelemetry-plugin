package org.cibseven.community.otel.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import org.cibseven.community.otel.BpmnMetricsService;
import org.cibseven.community.otel.OpenTelemetryProcessEnginePlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot AutoConfiguration for the CIB seven OpenTelemetry Plugin.
 *
 * <p>Automatically registers all required beans when:
 *
 * <ul>
 *   <li>The CIB seven engine is on the classpath
 *   <li>OpenTelemetry is on the classpath
 *   <li>No manual bean definitions override them
 * </ul>
 *
 * <p>No configuration needed — just add the dependency.
 */
@AutoConfiguration
@ConditionalOnClass(name = {
    "org.cibseven.bpm.engine.ProcessEngine",
    "io.opentelemetry.api.OpenTelemetry"
})
public class CibSevenOtelAutoConfiguration {

  /** Default constructor for Spring auto-configuration. */
  public CibSevenOtelAutoConfiguration() {}

  /** Creates the {@link BpmnMetricsService} bean if not already defined. */
  @Bean
  @ConditionalOnMissingBean
  public BpmnMetricsService bpmnMetricsService(OpenTelemetry openTelemetry) {
    return new BpmnMetricsService(openTelemetry);
  }

  /** Creates the {@link OpenTelemetryProcessEnginePlugin} bean if not already defined. */
  @Bean
  @ConditionalOnMissingBean
  public OpenTelemetryProcessEnginePlugin openTelemetryProcessEnginePlugin(
      OpenTelemetry openTelemetry, BpmnMetricsService metricsService) {
    return new OpenTelemetryProcessEnginePlugin(openTelemetry, metricsService);
  }
}
