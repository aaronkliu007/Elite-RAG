# Solution Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a human-controlled workbench that turns confirmed customer requirements into an editable, cited, versioned solution draft and exports it to Word.

**Architecture:** Spring Boot owns solution state, templates, human gates, retrieval calls, prompt versions, model routing, citation verification, and risk checks. Generation is asynchronous and section-scoped so completed work survives failures. Vue presents requirement confirmation, outline editing, section editing, evidence inspection, version comparison, and export.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, PostgreSQL, Flyway, OpenSearch-backed `RetrievalService`, Apache POI or docx4j selected by a checked-in spike, Vue 3, TypeScript, TipTap, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## Global Constraints

- Start only from tag `pilot-retrieval-v1` with permission and retrieval evaluation green.
- Requirement confirmation and outline confirmation are mandatory; APIs reject generation before both gates pass.
- Every generation run records requirement version, outline version, retrieval request, evidence IDs, model, prompt version, raw output, edits, latency, and cost.
- The model gateway is the only cloud-model boundary and transmits only the minimum authorized, sanitized evidence pack.
- Company capability, customer case, number, duration, price, and contractual claims require supporting evidence or an explicit human-confirmation marker.
- Section failures never erase confirmed requirements, outlines, completed sections, or user edits.
- No model output is automatically sent outside the system.

## Review Focus

- Invalid or partial structured model output must not overwrite the last valid requirement/outline version; Tasks 3 and 4 test schema rejection.
- Two editors saving the same section concurrently must receive an explicit conflict rather than silently losing text; Task 7 tests optimistic locking.
- A citation that exists but does not support its claim must be flagged and block final export unless a reviewer acknowledges it; Task 6 tests entailment decisions.
- Retrying a timed-out generation must not charge twice or create duplicate section versions when the first call later completes; Task 5 tests idempotency.
- Export must contain only authorized evidence and must remove scripts, external images, and unsafe hyperlinks from model/user content; Task 8 tests sanitization.

---

### Task 1: Solution, Template, and Version Schema

**Files:**
- Create: `apps/core-api/src/main/resources/db/migration/V003__solution_workbench.sql`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/Solution.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/SolutionVersion.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/SolutionSection.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/SolutionTemplate.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/SolutionAggregateTest.java`

**Interfaces:**
- Consumes: authenticated user, knowledge-space authorization, immutable customer-requirement upload.
- Produces: aggregate methods `create`, `reviseRequirements`, `confirmRequirements`, `reviseOutline`, `confirmOutline`, `saveSection`, `finalizeVersion`.

- [ ] **Step 1: Write aggregate invariant tests**

```java
@Test void cannotGenerateBeforeRequirementsAndOutlineAreConfirmed() {
    var solution = Solution.create(OWNER, CUSTOMER_DOC);
    assertThatThrownBy(() -> solution.beginSectionGeneration(SECTION_ID, RUN_ID))
        .isInstanceOf(HumanGateRequired.class);
}

@Test void confirmingNewRequirementsInvalidatesOutlineConfirmation() {
    var solution = confirmedSolution();
    solution.reviseRequirements(NEW_REQUIREMENTS, OWNER);
    assertThat(solution.outlineConfirmed()).isFalse();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=SolutionAggregateTest test`

- [ ] **Step 3: Add normalized schema and aggregate implementation**

Persist solution, immutable requirement version, immutable outline version, section, section version, generation run, evidence link, review decision, and export artifact. Use optimistic `row_version` on editable heads; immutable versions keep creator and timestamp.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=SolutionAggregateTest test
git add apps/core-api/src/main/resources/db/migration apps/core-api/src/main/java/com/yunjihai/rag/solution apps/core-api/src/test/java/com/yunjihai/rag/solution
git commit -m "feat: add versioned solution aggregate"
```

### Task 2: Provider-Neutral Model Gateway and Prompt Registry

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/model/ModelGateway.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/model/ModelRequest.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/model/ModelResponse.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/model/PromptRegistry.java`
- Create: `apps/core-api/src/main/resources/prompts/requirements/v1.st`
- Create: `apps/core-api/src/main/resources/prompts/outline/v1.st`
- Create: `apps/core-api/src/main/resources/prompts/section/v1.st`
- Create: `apps/core-api/src/main/resources/prompts/citation-verifier/v1.st`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/model/ModelGatewayTest.java`

**Interfaces:**
- Consumes: typed task, approved model policy, sanitized variables, response JSON Schema, deadline, idempotency key.
- Produces: `ModelGateway.execute(ModelRequest<T>, Class<T>): ModelResponse<T>` and immutable prompt version hashes.

- [ ] **Step 1: Write policy and redaction tests**

Test that non-approved provider/model pairs are rejected, secret/PII patterns are redacted according to policy, restricted documents cannot use an external provider, deadlines are propagated, and full evidence text is absent from ordinary logs.

- [ ] **Step 2: Implement the gateway around Spring AI**

```java
public interface ModelGateway {
    <T> ModelResponse<T> execute(ModelRequest<T> request, Class<T> responseType);
}

public record ModelResponse<T>(
        T value, String provider, String model, String promptVersion,
        int inputTokens, int outputTokens, Duration latency, String requestId) {}
```

Use structured output validation, provider adapters, timeouts, a bounded retry policy for transport failures only, and an idempotency ledger keyed by business operation plus prompt/model/config hash.

- [ ] **Step 3: Add a deterministic fake provider for all tests**

The fake reads fixture responses by task and never requires a cloud credential. Production profile refuses the fake provider.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=ModelGatewayTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/model apps/core-api/src/main/resources/prompts apps/core-api/src/test/java/com/yunjihai/rag/model
git commit -m "feat: add governed model gateway"
```

### Task 3: Requirement Extraction and Human Confirmation

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/requirements/RequirementSet.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/requirements/RequirementExtractionService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/api/RequirementController.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/requirements/RequirementExtractionServiceTest.java`
- Create: `apps/web/src/views/RequirementConfirmationView.vue`
- Create: `apps/web/src/views/__tests__/RequirementConfirmationView.spec.ts`

**Interfaces:**
- Consumes: authorized uploaded requirement document or structured manual input.
- Produces: immutable `RequirementSet` containing goals, scope, constraints, deliverables, stakeholders, dates, risks, unknowns, and source citations.

- [ ] **Step 1: Write invalid-JSON and preservation tests**

```java
@Test void invalidModelOutputDoesNotReplaceLastValidRequirements() {
    gateway.returnRaw("{not-json");
    assertThatThrownBy(() -> service.extract(SOLUTION_ID)).isInstanceOf(ModelOutputInvalid.class);
    assertThat(repository.current(SOLUTION_ID)).isEqualTo(PREVIOUS_REQUIREMENTS);
}
```

- [ ] **Step 2: Implement extraction with source citations and unknowns**

Require every extracted fact to reference the customer document location. Missing fields become `unknowns`; the model must not infer company promises. Confirmation creates a new immutable version and records confirmer/time.

- [ ] **Step 3: Implement confirmation UI**

Show side-by-side extracted fields and source locations, allow edits with reason, surface unknowns, and require explicit confirmation. Preserve unsaved edits on network retry.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=RequirementExtractionServiceTest test
corepack pnpm --dir apps/web test --run RequirementConfirmationView
git add apps/core-api apps/web
git commit -m "feat: confirm structured customer requirements"
```

### Task 4: Template-Driven Outline Generation and Confirmation

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/outline/Outline.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/outline/OutlineService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/api/OutlineController.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/outline/OutlineServiceTest.java`
- Create: `apps/web/src/views/OutlineEditorView.vue`
- Create: `apps/web/src/views/__tests__/OutlineEditorView.spec.ts`

**Interfaces:**
- Consumes: confirmed requirement version, selected template, and authorized preliminary evidence summaries.
- Produces: ordered outline nodes with stable section IDs, required/optional flags, objectives, retrieval hints, and risk rules.

- [ ] **Step 1: Write template and malformed-output tests**

Verify required template sections cannot disappear, duplicate section IDs are rejected, unsupported depth is rejected, and malformed model output leaves the last valid outline intact.

- [ ] **Step 2: Implement outline generation and editor**

Generate only after requirements confirmation. Users may reorder optional sections and edit objectives; required sections remain present. Confirmation stores a new immutable outline version and enables section generation.

- [ ] **Step 3: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=OutlineServiceTest test
corepack pnpm --dir apps/web test --run OutlineEditorView
git add apps/core-api apps/web
git commit -m "feat: add confirmable solution outlines"
```

### Task 5: Idempotent Per-Section Retrieval and Generation

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/SectionGenerationService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerationWorker.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerationState.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/generation/SectionGenerationServiceTest.java`
- Create: `apps/web/src/components/SectionGenerationStatus.vue`

**Interfaces:**
- Consumes: confirmed requirement and outline versions, section objective, retrieval service, model gateway.
- Produces: section-version draft, evidence links, generation trace, progress events, and typed insufficient-evidence result.

- [ ] **Step 1: Write human-gate, insufficient-evidence, and duplicate-retry tests**

```java
@Test void lateFirstResponseAndRetryCreateOneSectionVersion() {
    var key = service.start(command).idempotencyKey();
    service.retry(command);
    gateway.completeBoth(key);
    assertThat(sections.versionsFor(SECTION_ID)).hasSize(1);
    assertThat(costs.billableCallsFor(key)).isEqualTo(1);
}
```

- [ ] **Step 2: Implement asynchronous section state machine**

Use `PENDING`, `RETRIEVING`, `GENERATING`, `VERIFYING`, `SUCCEEDED`, `INSUFFICIENT_EVIDENCE`, `FAILED_RETRYABLE`, `FAILED_MANUAL`, `CANCELLED`. Save each state transition and emit progress; restarting resumes from the last durable state.

- [ ] **Step 3: Generate only from the sanitized evidence pack**

The prompt receives confirmed requirements, confirmed section objective, style/template rules, and evidence with opaque citation IDs. It must return structured paragraphs and cited claim IDs; no raw provider citation syntax enters the domain.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=SectionGenerationServiceTest test
git add apps/core-api apps/web
git commit -m "feat: generate recoverable cited sections"
```

### Task 6: Citation Verification and Document-Wide Risk Checks

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/CitationVerifier.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/RiskRuleEngine.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/ConsistencyChecker.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/verification/CitationVerifierTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/verification/RiskRuleEngineTest.java`

**Interfaces:**
- Consumes: generated claims, evidence text/locator, confirmed requirements, and all current sections.
- Produces: per-claim `SUPPORTED`, `PARTIAL`, `UNSUPPORTED`, `HUMAN_CONFIRMATION_REQUIRED`; cross-section conflict findings.

- [ ] **Step 1: Write support and high-risk field tests**

Verify a real but irrelevant citation is `UNSUPPORTED`; a partially supporting citation is `PARTIAL`; price, date, duration, customer name, performance figure, and legal promise require evidence plus human confirmation; conflicting figures across sections produce one blocking finding.

- [ ] **Step 2: Implement deterministic rules before model verification**

Run number/name/date extraction and exact-source checks first. Use the model verifier only for semantic entailment, with claim and evidence isolated as data. Store verifier model/prompt version and raw decision trace.

- [ ] **Step 3: Add finalization gate**

Finalization and export reject unresolved `UNSUPPORTED`, unacknowledged high-risk, and blocking conflict findings. A reviewer acknowledgment records actor, reason, timestamp, and finding version.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='CitationVerifierTest,RiskRuleEngineTest' test
git add apps/core-api/src/main/java/com/yunjihai/rag/solution/verification apps/core-api/src/test/java/com/yunjihai/rag/solution/verification
git commit -m "feat: verify citations and risky claims"
```

### Task 7: Collaborative Editing, Version Comparison, and Conflict Safety

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/api/SectionController.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/api/SectionConcurrencyTest.java`
- Create: `apps/web/src/views/SolutionEditorView.vue`
- Create: `apps/web/src/components/SectionEditor.vue`
- Create: `apps/web/src/components/VersionDiff.vue`
- Create: `apps/web/src/views/__tests__/SolutionEditorView.spec.ts`

**Interfaces:**
- Consumes: section text, expected row version, edit reason, cited claim anchors.
- Produces: immutable section versions and `409 Conflict` containing current version metadata when optimistic locking fails.

- [ ] **Step 1: Write concurrent-save test**

```java
@Test void staleEditorReceivesConflictWithoutOverwrite() {
    api.save(SECTION_ID, edit("A", version(4)));
    assertThatThrownBy(() -> api.save(SECTION_ID, edit("B", version(4))))
        .isInstanceOf(ConcurrentEdit.class);
    assertThat(repository.currentText(SECTION_ID)).isEqualTo("A");
}
```

- [ ] **Step 2: Implement editor and conflict UX**

Use TipTap with citation marks storing evidence IDs. Autosave sends expected version and never silently retries a `409`. The UI shows current server text versus local edits and lets the user copy or merge explicitly.

- [ ] **Step 3: Add version diff and single-section regeneration**

Regeneration creates a proposed version; it never overwrites human-edited current text until accepted.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=SectionConcurrencyTest test
corepack pnpm --dir apps/web test --run SolutionEditorView
git add apps/core-api apps/web
git commit -m "feat: add conflict-safe solution editing"
```

### Task 8: Safe Word Export

**Files:**
- Create: `docs/adr/0002-word-export-library.md`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/export/WordExportService.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/solution/export/WordExportServiceTest.java`
- Create: `apps/core-api/src/test/resources/export/reference-solution.json`

**Interfaces:**
- Consumes: finalized authorized solution version, template styles, verified citations.
- Produces: immutable DOCX artifact with title page, headings, tables, footnotes/endnotes, source appendix, and audit metadata.

- [ ] **Step 1: Run a checked-in spike comparing Apache POI and docx4j**

Render the reference solution with both libraries; select the smaller reliable implementation that preserves Chinese fonts, numbered headings, tables, page breaks, and citation notes. Record measured result and decision in ADR 0002; remove the rejected spike code before commit.

- [ ] **Step 2: Write export safety tests**

Unzip the generated DOCX and assert no `<script>`, external image relationship, JavaScript URL, unauthorized evidence title, or unrestricted file path exists. Assert every citation marker has a matching source appendix entry.

- [ ] **Step 3: Implement export and artifact authorization**

Generate only from the finalized immutable version after rerunning export-time authorization for all evidence. Store the export SHA-256 and object key; download requires solution access and audit logging.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=WordExportServiceTest test
git add docs/adr apps/core-api/src/main/java/com/yunjihai/rag/solution/export apps/core-api/src/test
git commit -m "feat: export verified solutions to Word"
```

### Task 9: Feedback, End-to-End Acceptance, and Phase Gate

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/solution/feedback/FeedbackService.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/SolutionWorkbenchAcceptanceTest.java`
- Create: `apps/web/e2e/solution-workbench.spec.ts`
- Create: `evaluation/generation/golden-v1.jsonl`
- Create: `docs/runbooks/solution-workbench.md`

**Interfaces:**
- Consumes: accepted/rejected proposed section, edit distance, reason code, user comment, final solution.
- Produces: feedback facts and the phase acceptance report.

- [ ] **Step 1: Implement structured feedback**

Reason codes are `ACCEPTED`, `FACT_ERROR`, `MISSING_EVIDENCE`, `IRRELEVANT`, `OUTDATED`, `STYLE`, `DUPLICATE`, `SCOPE_MISMATCH`, `OTHER`. Store proposed and final version IDs rather than copying sensitive content into analytics tables.

- [ ] **Step 2: Write complete browser acceptance test**

Upload a customer requirement, confirm extracted requirements, choose template, edit and confirm outline, generate two sections, inspect citations, reject one proposal, edit and accept it, resolve high-risk findings, finalize, export DOCX, and verify another unauthorized user cannot open the solution or evidence.

- [ ] **Step 3: Run the full phase verification**

```powershell
.\mvnw.cmd verify
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
corepack pnpm --dir apps/web exec playwright test e2e/solution-workbench.spec.ts
powershell -ExecutionPolicy Bypass -File scripts/run-retrieval-eval.ps1
```

Expected: all commands pass with fake model provider; no cloud credential is required in CI.

- [ ] **Step 4: Commit and tag**

```powershell
git add apps evaluation docs/runbooks
git commit -m "test: complete solution workbench acceptance"
git tag pilot-workbench-v1
```
