# CIB seven OpenTelemetry Plugin

## Project Purpose

Open-source ProcessEnginePlugin for **CIB seven** (the open-source Camunda 7 fork) that automatically instruments BPMN process execution with **OpenTelemetry traces and metrics** — requiring zero changes to existing BPMN files or application code.

- **License:** Apache 2.0
- **Target org:** `cibseven-community-hub` on GitHub
- **Maven coordinates:** `org.cibseven.community:cibseven-opentelemetry-plugin:1.0.0-SNAPSHOT`
- **Java:** 17+
- **Build:** Maven (no Gradle)

## Tech Stack

| Dependency | Version | Scope |
|---|---|---|
| CIB seven engine | 2.1.0 | provided |
| Spring Boot | 3.2.5 | provided |
| OpenTelemetry Instrumentation BOM | 2.10.0 | compile |
| JUnit 5 | 5.10.2 | test |
| Mockito | 5.11.0 | test |
| Lombok | — | optional |

## Architecture

The plugin hooks into the CIB seven process engine via the `ProcessEnginePlugin` extension point. It registers once at startup and instruments all processes globally.

### Key Components (package: `org.cibseven.community.otel`)

| Class | Role |
|---|---|
| `OpenTelemetryProcessEnginePlugin` | Entry point. Spring `@Component`. Implements `ProcessEnginePlugin`, wires `BpmnParseListener` into engine config. |
| `OpenTelemetryBpmnParseListener` | Extends `AbstractBpmnParseListener`. Attaches `ExecutionListener` on service tasks, user tasks, gateways, end events. |
| `OpenTelemetryExecutionListener` | Fires on task START/END. Creates/ends OTel spans with BPMN attributes. |
| `OpenTelemetryTaskListener` | Listens on user task lifecycle events. |
| `BpmnMetricsService` | Manages OTel Meters: counters (`bpmn.process.started`, `.ended`, `bpmn.task.started`, `.ended`) and histograms (`bpmn.task.duration`, `bpmn.process.duration`). |
| `TraceContextPropagator` | Propagates trace context across async boundaries (external task workers via REST). |
| `autoconfigure/CibSevenOtelAutoConfiguration` | Spring Boot auto-configuration entry point. |

### Auto-configuration

Registered via `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Traces & Metrics

### Trace hierarchy

```
Trace: <process-definition-key> (instance ID: <uuid>)
  +-- Span: <task-id> [duration]
       Attributes: bpmn.process.name, bpmn.task.name, bpmn.outcome
```

### Metrics

| Metric | Type | Attributes |
|---|---|---|
| `bpmn.process.started` | Counter | process.name |
| `bpmn.process.ended` | Counter | process.name, process.outcome |
| `bpmn.process.duration` | Histogram | process.name, process.outcome |
| `bpmn.task.started` | Counter | process.name, task.name |
| `bpmn.task.ended` | Counter | process.name, task.name, task.outcome |
| `bpmn.task.duration` | Histogram | process.name, task.name, task.outcome |
| `bpmn.task.active` | Gauge | process.name, task.name |

## Repository Layout

```
pom.xml
src/main/java/org/cibseven/community/otel/   # plugin source
src/main/resources/META-INF/spring/           # auto-config registration
src/test/java/                                # tests
docker-example/                               # Docker Compose example stack
grafana-dashboard.json                        # (planned) pre-built Grafana dashboard
```

## Code Style — Google Java Style Guide

All code follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html). Key rules enforced:

- **Indentation:** 2 spaces (no tabs)
- **Column limit:** 100 characters
- **Braces:** Always required for `if`, `else`, `for`, `while`, `do`, even single-line bodies
- **No column alignment:** No extra spaces to align `=`, field names, etc.
- **Import ordering:** static imports first (separated by blank line), then non-static; no wildcard imports
- **Naming:**
  - Classes: `UpperCamelCase`
  - Methods/fields: `lowerCamelCase`
  - Constants (`static final`): `UPPER_SNAKE_CASE`
  - Packages: all lowercase, no underscores
- **Javadoc:** Required on all public classes and public methods. Use `<p>` for paragraph breaks. `@param`/`@return`/`@throws` only when they add value beyond the obvious.
- **Continuation indent:** 4 spaces (for line wraps)
- **One variable per declaration**
- **Switch:** Arrow-style (`case X ->`) preferred (Java 17+)
- **Annotations:** Each on its own line above the declaration

## Development Guidelines

- End users activate the plugin by adding **one Maven dependency** — no BPMN changes needed.
- CIB seven engine and Spring Boot are `provided` scope — users bring their own.
- Must remain compatible with Camunda 7 (CIB seven is API-compatible).
- Use `io.opentelemetry` APIs directly (via the instrumentation BOM), not vendor-specific SDKs.
- Tests use JUnit 5 + Mockito. Integration tests target embedded CIB seven.
- Run tests: `mvn test`
- Build: `mvn clean package`

## Implementation Phases

1. **Foundation** — Plugin skeleton, parse listener wiring, Spring Boot auto-config, CI/CD
2. **Tracing** — Span lifecycle, BPMN attributes, parent-child spans, error recording, context propagation
3. **Metrics** — Counters, histograms, Prometheus endpoint, Grafana dashboard, performance testing
4. **Release** — Maven Central publish, README, Docker Compose example, community announcement
