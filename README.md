# cibseven-opentelemetry-plugin

> **Automatic OpenTelemetry instrumentation for CIB seven (Camunda 7 fork)**

[![Apache 2.0 License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![CIB seven](https://img.shields.io/badge/cibseven-2.x-green.svg)](https://cibseven.org)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-2.x-orange.svg)](https://opentelemetry.io)

This plugin automatically instruments every BPMN process execution in CIB seven with:
- **Distributed traces** (spans per process instance and per task) → Jaeger / Grafana Tempo
- **Metrics** (counters, histograms for start/end/duration/outcome) → Prometheus / Grafana

**Zero changes to BPMN files required.** Add one Maven dependency and you're done.

---

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>org.cibseven.community</groupId>
    <artifactId>cibseven-opentelemetry-plugin</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Configure your `application.properties`

```properties
spring.application.name=my-bpm-app

# OTLP endpoint (point to your OTel Collector or Jaeger)
otel.exporter.otlp.endpoint=http://localhost:4318
otel.exporter.otlp.protocol=http/protobuf
otel.traces.exporter=otlp
otel.metrics.exporter=otlp
otel.traces.sampler=parentbased_always_on
```

### 3. Start your app — that's it!

Every process instance is now traced automatically.

---

## What You Get

### Traces (in Jaeger / Grafana Tempo)

```
Trace: loan-application (instance abc-123)
  ├── receive-application         50ms
  ├── credit-check               2.3s   ← bottleneck visible!
  ├── manual-review                4h   ← human task duration
  └── send-decision-letter       200ms
```

Each span carries BPMN attributes:
| Attribute | Example |
|---|---|
| `bpmn.process.definition` | `loan-application:1:abc` |
| `bpmn.process.instance`   | `abc-123` |
| `bpmn.activity.id`        | `creditCheck` |
| `bpmn.activity.name`      | `Credit Check` |
| `bpmn.activity.type`      | `serviceTask` |
| `bpmn.outcome`            | `completed` / `rejected` |

### Metrics (in Prometheus / Grafana)

| Metric | Type | Description |
|---|---|---|
| `bpmn.process.started`  | Counter   | Process instances started |
| `bpmn.process.ended`    | Counter   | Process instances ended (with outcome) |
| `bpmn.process.duration` | Histogram | Full process duration in ms |
| `bpmn.task.started`     | Counter   | Tasks started |
| `bpmn.task.ended`       | Counter   | Tasks ended (with outcome) |
| `bpmn.task.duration`    | Histogram | Task duration in ms |
| `bpmn.task.active`      | Gauge     | Tasks currently in progress |

### Example Grafana Queries (PromQL)

```promql
# Completion rate per task
rate(bpmn_task_ended{outcome="completed"}[5m]) / rate(bpmn_task_started[5m])

# Average task duration
rate(bpmn_task_duration_sum[5m]) / rate(bpmn_task_duration_count[5m])

# Tasks currently in progress
bpmn_task_active

# Rejection rate
rate(bpmn_task_ended{outcome="rejected"}[5m]) / rate(bpmn_task_started[5m])
```

---

## Run the Example Stack

```bash
cd docker-example
docker-compose up -d
```

Then open:
- **Jaeger UI** → http://localhost:16686
- **Grafana**   → http://localhost:3000 (admin / admin)
- **Prometheus**→ http://localhost:9090

---

## Architecture

```
CIB seven (Spring Boot)
        │
        │  ProcessEnginePlugin (auto-registered at startup)
        ▼
OpenTelemetryBpmnParseListener
        │   (fires once per process definition at deploy time)
        ▼
OpenTelemetryExecutionListener  ←── all task types
OpenTelemetryTaskListener       ←── user tasks (CREATE/COMPLETE/DELETE)
        │
        ▼
OpenTelemetry SDK
        ├── Tracer  → Spans → OTLP → Jaeger
        └── Meter   → Metrics → Prometheus → Grafana
```

---

## Recording Task Outcomes

Set a process variable `taskOutcome` before completing a task to record a custom outcome:

```java
// In your JavaDelegate or task complete handler:
taskService.setVariable(taskId, "taskOutcome", "rejected");
taskService.complete(taskId);
// → span attribute bpmn.task.outcome = "rejected"
// → metric: bpmn.task.ended{outcome="rejected"}
```

---

## Requirements

- Java 17+
- CIB seven 2.x (or Camunda 7.x — API compatible)
- Spring Boot 3.x
- OpenTelemetry instrumentation BOM 2.x

---

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes
4. Open a Pull Request

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)

---

## Related Projects

- [CIB seven](https://cibseven.org) — Open-source Camunda 7 fork
- [cibseven-community-hub](https://github.com/cibseven-community-hub) — Community extensions
- [OpenTelemetry Java](https://github.com/open-telemetry/opentelemetry-java)
