package org.cibseven.community.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central service for all BPMN-related OpenTelemetry metrics.
 *
 * <p>Exposed metrics:
 * <ul>
 *   <li>{@code bpmn.process.started} — counter, process.name
 *   <li>{@code bpmn.process.ended} — counter, process.name, process.outcome
 *   <li>{@code bpmn.process.duration} — histogram (ms), process.name, process.outcome
 *   <li>{@code bpmn.task.started} — counter, process.name, task.name
 *   <li>{@code bpmn.task.ended} — counter, process.name, task.name, task.outcome
 *   <li>{@code bpmn.task.duration} — histogram (ms), process.name, task.name, task.outcome
 *   <li>{@code bpmn.task.active} — gauge, process.name, task.name
 * </ul>
 */
@Service
public class BpmnMetricsService {

  private static final Logger log = LoggerFactory.getLogger(BpmnMetricsService.class);

  static final AttributeKey<String> KEY_PROCESS_NAME = AttributeKey.stringKey("process.name");
  static final AttributeKey<String> KEY_TASK_NAME = AttributeKey.stringKey("task.name");
  static final AttributeKey<String> KEY_OUTCOME = AttributeKey.stringKey("outcome");

  private final LongCounter processStarted;
  private final LongCounter processEnded;
  private final LongHistogram processDuration;
  private final LongCounter taskStarted;
  private final LongCounter taskEnded;
  private final LongHistogram taskDuration;

  /** Track start times for duration calculation: key = "process/task:instanceId[:taskName]". */
  private final ConcurrentHashMap<String, Long> startTimes = new ConcurrentHashMap<>();

  /** Active task counters per process+task combination (for gauge). */
  private final ConcurrentHashMap<String, AtomicLong> activeTasks = new ConcurrentHashMap<>();

  /** Creates the metrics service and registers all BPMN metrics with the given OTel instance. */
  public BpmnMetricsService(OpenTelemetry openTelemetry) {
    Meter meter = openTelemetry.meterBuilder("cibseven-bpmn-metrics")
        .setInstrumentationVersion("1.0.0")
        .build();

    this.processStarted = meter.counterBuilder("bpmn.process.started")
        .setDescription("Number of BPMN process instances started")
        .setUnit("{instance}")
        .build();

    this.processEnded = meter.counterBuilder("bpmn.process.ended")
        .setDescription("Number of BPMN process instances ended")
        .setUnit("{instance}")
        .build();

    this.processDuration = meter.histogramBuilder("bpmn.process.duration")
        .setDescription("Duration of complete BPMN process instances")
        .setUnit("ms")
        .ofLongs()
        .build();

    this.taskStarted = meter.counterBuilder("bpmn.task.started")
        .setDescription("Number of BPMN task instances started")
        .setUnit("{task}")
        .build();

    this.taskEnded = meter.counterBuilder("bpmn.task.ended")
        .setDescription("Number of BPMN task instances ended")
        .setUnit("{task}")
        .build();

    this.taskDuration = meter.histogramBuilder("bpmn.task.duration")
        .setDescription("Duration of individual BPMN tasks")
        .setUnit("ms")
        .ofLongs()
        .build();

    meter.gaugeBuilder("bpmn.task.active")
        .setDescription("Number of currently active (in-progress) BPMN tasks")
        .setUnit("{task}")
        .ofLongs()
        .buildWithCallback(measurement -> {
          activeTasks.forEach((key, count) -> {
            String[] parts = key.split("\\|", 2);
            if (parts.length == 2) {
              measurement.record(
                  count.get(),
                  Attributes.of(KEY_PROCESS_NAME, parts[0], KEY_TASK_NAME, parts[1]));
            }
          });
        });

    log.info("[CIB seven OTel] BpmnMetricsService initialised — 7 metrics registered");
  }

  // Process events

  /** Records the start of a BPMN process instance. */
  public void onProcessStart(String processName, String instanceId) {
    startTimes.put("process:" + instanceId, System.currentTimeMillis());
    processStarted.add(1, Attributes.of(KEY_PROCESS_NAME, processName));
    log.debug("[CIB seven OTel] process.started process={}", processName);
  }

  /** Records the end of a BPMN process instance and its duration. */
  public void onProcessEnd(String processName, String instanceId, String outcome) {
    Attributes attrs = Attributes.of(KEY_PROCESS_NAME, processName, KEY_OUTCOME, outcome);
    processEnded.add(1, attrs);

    Long start = startTimes.remove("process:" + instanceId);
    if (start != null) {
      processDuration.record(System.currentTimeMillis() - start, attrs);
    }
    log.debug("[CIB seven OTel] process.ended process={} outcome={}", processName, outcome);
  }

  // Task events

  /** Records the start of a BPMN task and increments the active task gauge. */
  public void onTaskStart(String processName, String taskName, String instanceId) {
    String timeKey = "task:" + instanceId + ":" + taskName;
    String activeKey = processName + "|" + taskName;

    startTimes.put(timeKey, System.currentTimeMillis());
    activeTasks.computeIfAbsent(activeKey, k -> new AtomicLong(0)).incrementAndGet();

    taskStarted.add(1, Attributes.of(KEY_PROCESS_NAME, processName, KEY_TASK_NAME, taskName));
    log.debug("[CIB seven OTel] task.started process={} task={}", processName, taskName);
  }

  /** Records the end of a BPMN task, its duration, and decrements the active task gauge. */
  public void onTaskEnd(String processName, String taskName, String instanceId, String outcome) {
    String timeKey = "task:" + instanceId + ":" + taskName;
    String activeKey = processName + "|" + taskName;

    Attributes attrs =
        Attributes.of(KEY_PROCESS_NAME, processName, KEY_TASK_NAME, taskName, KEY_OUTCOME, outcome);
    taskEnded.add(1, attrs);

    Long start = startTimes.remove(timeKey);
    if (start != null) {
      taskDuration.record(System.currentTimeMillis() - start, attrs);
    }

    AtomicLong active = activeTasks.get(activeKey);
    if (active != null && active.decrementAndGet() <= 0) {
      activeTasks.remove(activeKey);
    }

    log.debug("[CIB seven OTel] task.ended process={} task={} outcome={}",
        processName, taskName, outcome);
  }
}
