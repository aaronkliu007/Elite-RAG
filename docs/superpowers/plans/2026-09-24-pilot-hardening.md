# Pilot Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the accepted workbench into a controlled 10–20 user pilot with measurable security, reliability, performance, recovery, cost, and business outcomes.

**Architecture:** Hardening wraps the existing application boundaries rather than adding new product scope. Security filters, tracing, metrics, recovery automation, load tests, and acceptance reporting exercise the same production paths used by pilot users. Release is blocked automatically when ACL, retrieval, generation, backup, or business evidence is missing.

**Tech Stack:** Spring Boot Actuator, Micrometer, OpenTelemetry, Prometheus-compatible metrics, structured JSON logs, PostgreSQL/MinIO/OpenSearch backup tools, OWASP ZAP, dependency scanning, k6, JUnit, Playwright, Docker/Kubernetes manifests selected for the target environment.

**Spec:** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## Global Constraints

- Start only from tag `pilot-workbench-v1` with all earlier acceptance suites green.
- Hardening adds no new business scenario beyond solution generation.
- Production documents never enter test, performance, or security-test environments; use synthetic or approved anonymized fixtures.
- Security logs include actor/resource/outcome IDs but exclude secrets and full sensitive document text.
- Availability baseline is 99.5% during business hours; ordinary search P95 is at most 3 seconds.
- PostgreSQL and object-store recovery objectives are RPO at most 24 hours and RTO at most 4 hours.
- Permission automation remains 100% green; any unauthorized hit blocks release.

## Review Focus

- Hidden text, zero-width characters, or instructions inside retrieved documents must remain data and never override system policy; Task 1 tests indirect prompt injection.
- A cloud-model outage or delayed response must not lose confirmed work or duplicate billing; Task 2 runs fault injection.
- Logs, traces, metrics, and error reports must not contain document bodies, secrets, signed URLs, or raw tokens; Task 3 tests redaction.
- Restoring PostgreSQL and MinIO to different points in time must be detected and reconciled before serving documents; Task 4 tests consistency repair.
- Load shedding must protect storage and model budgets instead of allowing an upload/generation burst to exhaust the platform; Task 5 tests bounded concurrency and `429` responses.

---

### Task 1: RAG Security and Prompt-Injection Controls

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/security/content/UntrustedContentScanner.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/security/content/PromptBoundaryBuilder.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/security/output/GeneratedContentSanitizer.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/security/PromptInjectionSecurityTest.java`
- Create: `security/fixtures/indirect-prompt-injection.jsonl`
- Create: `docs/runbooks/ai-security-response.md`

**Interfaces:**
- Consumes: uploaded/parsing text, user input, evidence, generated HTML/links.
- Produces: findings with severity and location, safely delimited prompts, sanitized output, and blocking decisions.

- [ ] **Step 1: Add attack fixtures and failing tests**

Include hidden white-on-white text, zero-width characters, base64-like instruction blocks, “ignore previous instructions”, malicious Markdown images, `javascript:` links, prompt leakage requests, and retrieval poisoning that names internal policies.

```java
@Test void retrievedInstructionRemainsQuotedData() {
    var prompt = boundaries.build(SYSTEM_POLICY, USER_REQUEST, maliciousEvidence());
    assertThat(prompt.system()).isEqualTo(SYSTEM_POLICY);
    assertThat(prompt.evidence()).contains("UNTRUSTED_EVIDENCE");
    assertThat(prompt.evidence()).doesNotContain("role=system");
}
```

- [ ] **Step 2: Implement layered scanning and strict prompt boundaries**

Normalize Unicode while retaining original source, flag invisible-control patterns, isolate evidence in structured messages, and forbid evidence content from becoming tool definitions or system messages. Do not claim the scanner eliminates prompt injection; block high-confidence attacks and audit the rest.

- [ ] **Step 3: Implement generated-content sanitization**

Allow only approved formatting tags and `https` links after host policy checks. Proxy no remote images. Strip event handlers, scripts, embedded objects, data URLs, and local file paths.

- [ ] **Step 4: Run security tests and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=PromptInjectionSecurityTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/security apps/core-api/src/test security docs/runbooks/ai-security-response.md
git commit -m "security: harden untrusted RAG content"
```

### Task 2: Fault Tolerance and Safe Degradation

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/resilience/DependencyPolicy.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/resilience/FaultInjectionTest.java`
- Create: `infra/compose/toxiproxy.json`
- Modify: `infra/compose/compose.yaml`
- Create: `docs/runbooks/dependency-outage.md`

**Interfaces:**
- Consumes: parser, PostgreSQL, MinIO, OpenSearch, embedding, reranker, and chat-model calls.
- Produces: explicit deadlines, bounded retries, circuit-breaker state, degradation flags, and durable resumption.

- [ ] **Step 1: Write dependency fault matrix tests**

Inject timeout, connection reset, 429, 500, malformed body, and 30-second delay. Verify: parser job retries or goes manual; OpenSearch failure preserves data; embedding failure falls back to lexical; reranker failure uses fused rank; chat-model failure preserves confirmed content; PostgreSQL failure fails closed; MinIO failure blocks download without leaking signed URLs.

- [ ] **Step 2: Implement dependency-specific policies**

Retry only idempotent transport failures, honor caller deadlines and provider `Retry-After`, cap concurrency per dependency, and open circuits without hiding the reason from operators. Never retry structured validation failures.

- [ ] **Step 3: Run fault suite and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=FaultInjectionTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/resilience apps/core-api/src/test infra/compose docs/runbooks/dependency-outage.md
git commit -m "feat: add safe dependency degradation"
```

### Task 3: Tracing, Metrics, Cost, and Redacted Logs

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/observability/CorrelationIdFilter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/observability/CostMeter.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/observability/TelemetryRedactionTest.java`
- Create: `infra/observability/prometheus.yml`
- Create: `infra/observability/alerts.yml`
- Create: `docs/runbooks/observability.md`

**Interfaces:**
- Consumes: request, job, retrieval, model-call, and export lifecycle events.
- Produces: correlation IDs, OpenTelemetry spans, bounded-cardinality metrics, redacted structured logs, and cost records.

- [ ] **Step 1: Write telemetry leakage tests**

Send fixtures containing API key patterns, signed object URLs, customer names, document bodies, access tokens, and prompt contents; capture logs/spans/error responses and assert none of the sensitive values appear.

- [ ] **Step 2: Instrument critical paths**

Trace Web → Java → Python/OpenSearch/model gateway with one correlation ID. Record upload/parse/index success, queue depth, search/rerank/model latency, empty retrieval, citation failure, model refusal, token usage, single-solution cost, authorization denial, and active-user/acceptance counters.

- [ ] **Step 3: Add actionable alerts**

Alert on permission-test failure in CI, ingestion backlog age, parser failure spike, OpenSearch projection lag, model error/cost spike, retrieval P95 breach, backup failure, and storage-capacity threshold. Do not alert on individual expected authorization denials.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=TelemetryRedactionTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/observability apps/core-api/src/test infra/observability docs/runbooks/observability.md
git commit -m "feat: add redacted end-to-end telemetry"
```

### Task 4: Backup, Restore, and Cross-Store Reconciliation

**Files:**
- Create: `scripts/backup.ps1`
- Create: `scripts/restore.ps1`
- Create: `scripts/reconcile-stores.ps1`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/operations/StoreReconciliationService.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/operations/StoreReconciliationTest.java`
- Create: `docs/runbooks/backup-restore.md`

**Interfaces:**
- Consumes: PostgreSQL logical/physical backup, versioned MinIO bucket snapshot, OpenSearch rebuild command.
- Produces: manifest containing snapshot times, checksums, schema version, object counts, and reconciliation report.

- [ ] **Step 1: Write inconsistent-restore tests**

Test database references a missing object, object exists without a database row, published version lacks chunks, stale OpenSearch ACL, and restored DB/object timestamps exceed the allowed consistency window.

- [ ] **Step 2: Implement backup and restore scripts with manifests**

Backup PostgreSQL and MinIO; do not treat OpenSearch as authoritative backup. Restore into an isolated validation environment, run migrations, verify hashes/counts, rebuild OpenSearch, and only then permit traffic.

- [ ] **Step 3: Implement reconciliation behavior**

Missing source blob blocks the affected document and raises a critical finding; orphan blob is quarantined; missing/stale index is rebuilt; ACL mismatch is repaired from PostgreSQL before search resumes.

- [ ] **Step 4: Run timed recovery drill**

Record timestamps proving RPO ≤24h and RTO ≤4h using the approved pilot dataset. Store the signed drill report under `docs/runbooks/evidence/`.

- [ ] **Step 5: Commit**

```powershell
git add scripts apps/core-api/src/main/java/com/yunjihai/rag/operations apps/core-api/src/test docs/runbooks
git commit -m "ops: add verified backup and store recovery"
```

### Task 5: Load, Concurrency, and Budget Protection

**Files:**
- Create: `performance/k6/pilot-load.js`
- Create: `performance/k6/fixtures.json`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/resilience/AdmissionController.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/resilience/AdmissionControllerTest.java`
- Create: `docs/runbooks/capacity.md`

**Interfaces:**
- Consumes: authenticated upload, search, section generation, edit, citation, and export endpoints.
- Produces: bounded queues, per-user/department budgets, `429` with retry guidance, and a reproducible performance report.

- [ ] **Step 1: Write admission tests**

Verify per-user generation limit, tenant/department budget limit, upload byte/concurrency limit, queue maximum, fair scheduling across users, and that excess requests return `429` without creating jobs or model costs.

- [ ] **Step 2: Implement bounded admission and budget reservation**

Reserve estimated cost before model execution and settle actual cost after completion. Expired/failed reservations are released idempotently. Administrative overrides are audited and time-limited.

- [ ] **Step 3: Create 20-user k6 scenario**

Model concurrent browsing, 20% uploads, repeated searches, five parallel section generations, edits, citation views, and two exports using synthetic documents. Define thresholds: search `p(95)<3000`, HTTP failure rate `<1%` excluding expected `429`, and no unauthorized response.

- [ ] **Step 4: Run, tune only measured bottlenecks, and commit report**

```powershell
k6 run performance/k6/pilot-load.js
```

Commit the script, capacity assumptions, measured P50/P95/P99, saturation point, and selected queue/concurrency limits; do not commit production data.

```powershell
git add performance apps/core-api/src/main/java/com/yunjihai/rag/resilience apps/core-api/src/test docs/runbooks/capacity.md
git commit -m "perf: protect pilot capacity and model budget"
```

### Task 6: Application Security and Supply-Chain Gate

**Files:**
- Create: `security/zap/rules.tsv`
- Create: `security/zap/context.yaml`
- Create: `scripts/security-gate.ps1`
- Create: `docs/runbooks/security-testing.md`
- Modify: `.github/workflows/verify.yml`

**Interfaces:**
- Consumes: built Java/Python/Node artifacts and a disposable test deployment.
- Produces: dependency, secret, container, SAST, DAST, authorization, and RAG red-team reports.

- [ ] **Step 1: Add automated security checks**

Run Maven dependency analysis, Python and pnpm lockfile audits, secret scanning, container scanning, SAST, OWASP ZAP authenticated scan, authorization suites, and prompt-injection fixtures. Pin scanner versions in CI.

- [ ] **Step 2: Define failure policy**

Release fails on committed secret, critical/high exploitable vulnerability without documented accepted risk, any authorization failure, active XSS/output-injection finding, or prompt test that causes a prohibited action/data disclosure. Accepted risks require owner, expiry, compensating control, and issue link.

- [ ] **Step 3: Run and commit**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/security-gate.ps1
git add security scripts/security-gate.ps1 docs/runbooks/security-testing.md .github/workflows/verify.yml
git commit -m "security: gate pilot releases"
```

### Task 7: Pilot Analytics, Acceptance Report, and Release Decision

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/pilot/PilotMetricsService.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/pilot/PilotMetricsServiceTest.java`
- Create: `docs/pilot/baseline-template.md`
- Create: `docs/pilot/weekly-review-template.md`
- Create: `docs/pilot/acceptance-template.md`
- Create: `scripts/pilot-acceptance.ps1`

**Interfaces:**
- Consumes: anonymized duration, activity, generated/reused content, citation, feedback, reliability, security, recovery, and cost metrics.
- Produces: acceptance decision `LAUNCH`, `EXTEND_QUALITY`, or `STOP_REASSESS` with evidence links.

- [ ] **Step 1: Write metric classification tests**

```java
@Test void efficiencyPassButCitationFailExtendsQualityWork() {
    var result = service.decide(metrics().timeReduction(.60).citationCoverage(.82).securityIncidents(0));
    assertThat(result.decision()).isEqualTo(EXTEND_QUALITY);
}
```

Test all three decisions, zero-baseline data, missing weeks, and any security incident forcing a review rather than automatic launch.

- [ ] **Step 2: Implement privacy-minimized pilot aggregation**

Aggregate by week and cohort; avoid storing document text in analytics. Compute time reduction against signed baseline, weekly active rate, workflow completion, generation/reuse rate, citation coverage/support precision, edit/reject reasons, cost per solution, availability, and latency.

- [ ] **Step 3: Run final release gate**

```powershell
.\mvnw.cmd verify
uv run --project workers/document-parser pytest
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
corepack pnpm --dir apps/web exec playwright test
powershell -ExecutionPolicy Bypass -File scripts/run-retrieval-eval.ps1
powershell -ExecutionPolicy Bypass -File scripts/security-gate.ps1
powershell -ExecutionPolicy Bypass -File scripts/pilot-acceptance.ps1
```

Expected: all engineering gates pass; the business report contains baseline and observed values rather than fabricated placeholders.

- [ ] **Step 4: Commit, review, and tag only after evidence is attached**

```powershell
git add apps docs/pilot scripts
git commit -m "docs: record pilot acceptance evidence"
git tag pilot-ready-v1
```
