# Trusted Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver permission-safe hybrid retrieval with rebuildable OpenSearch projections, reranking, source-location citations, and repeatable offline quality evaluation.

**Architecture:** Published canonical document nodes are projected from PostgreSQL into OpenSearch. Spring Boot expands the caller's principals, applies ACL and metadata filters before BM25/vector retrieval, fuses ranks, reranks candidates, and revalidates every selected document against PostgreSQL before emitting an evidence pack. Evaluation uses a versioned JSONL dataset and blocks regressions.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, PostgreSQL, OpenSearch 3.x, OpenSearch Java Client, Testcontainers, JUnit 5, Vue 3, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## Global Constraints

- Start only from tag `pilot-foundation-v1` with the foundation verification green.
- PostgreSQL remains authoritative; deleting the OpenSearch index and rebuilding it must not lose business data.
- Search includes only current `PUBLISHED` versions effective at query time.
- ACL filtering happens inside each lexical/vector query; PostgreSQL authorization runs again before response assembly.
- Default candidate settings are configurable: lexical 50, vector 50, fused 30, final evidence 8–15.
- Every citation resolves to document version plus page, slide, table/range, or structured node.
- Retrieval/model configuration changes require evaluation against the same versioned golden set.

## Review Focus

- An ACL change racing with a search must not leak a previously authorized hit; Task 5 rechecks selected evidence transactionally.
- Deleted, archived, expired, or superseded versions must disappear after projection repair; Tasks 2 and 3 test tombstones and rebuilds.
- Empty vector results must still permit lexical evidence, and empty lexical results must still permit semantic evidence; Task 4 tests both degradations.
- Duplicate chunks and many hits from one document must not crowd out diverse evidence; Task 5 tests deduplication and per-document caps.
- A citation with missing page/bbox/table coordinates must be rejected rather than rendered as precise evidence; Task 6 tests locator validation.

---

### Task 1: Retrieval Domain Contracts

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/RetrievalQuery.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/Candidate.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/Evidence.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/EvidencePack.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/RetrievalService.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/RetrievalContractTest.java`

**Interfaces:**
- Consumes: user ID, confirmed query text, optional industry/product/year filters, and required result limit.
- Produces: `RetrievalService.retrieve(RetrievalQuery): EvidencePack` with immutable evidence IDs and locators.

- [ ] **Step 1: Write contract invariants**

```java
@Test void evidenceRequiresPublishedVersionAndSourceLocator() {
    assertThatThrownBy(() -> new Evidence(ID, DOC, VERSION, "text", null, 0.9))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test void requestedEvidenceLimitMustRemainWithinApprovedRange() {
    assertThatThrownBy(() -> query.withLimit(16)).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run and observe failure**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=RetrievalContractTest test`

- [ ] **Step 3: Implement explicit records and ports**

```java
public interface RetrievalService {
    EvidencePack retrieve(RetrievalQuery query);
}

public sealed interface SourceLocator permits PageLocator, SlideLocator, TableLocator, MarkdownLocator {}
```

Keep OpenSearch and model-provider types out of the domain API.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=RetrievalContractTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval apps/core-api/src/test/java/com/yunjihai/rag/retrieval
git commit -m "feat: define trusted retrieval contracts"
```

### Task 2: Chunking and Projection Outbox

**Files:**
- Create: `apps/core-api/src/main/resources/db/migration/V002__retrieval_projection.sql`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index/ChunkingService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index/ProjectionOutbox.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index/ProjectionEventHandler.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/index/ChunkingServiceTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/index/ProjectionOutboxTest.java`

**Interfaces:**
- Consumes: canonical nodes and publish/archive events from the foundation.
- Produces: deterministic `RetrievalChunk` rows and outbox events `UPSERT_VERSION`, `DELETE_VERSION`, `REBUILD_SPACE`.

- [ ] **Step 1: Write format-aware chunk tests**

Test that headings remain with child paragraphs, PPT slides remain intact, table headers repeat with row groups, Markdown code fences are not split, and identical canonical input generates stable chunk IDs.

- [ ] **Step 2: Write supersession/tombstone transaction test**

```java
@Test void publishingNewVersionWritesUpsertAndOldVersionDeleteAtomically() {
    publications.publish(VERSION_2);
    assertThat(outbox.pendingTypes()).containsExactlyInAnyOrder("UPSERT_VERSION", "DELETE_VERSION");
}
```

- [ ] **Step 3: Run tests and verify failure**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest='ChunkingServiceTest,ProjectionOutboxTest' test`

- [ ] **Step 4: Implement chunking and transactional outbox**

Persist parent node, title path, source locator, document/version IDs, metadata, effective dates, and allowed principal IDs with every chunk. Insert projection events in the same PostgreSQL transaction that publishes, archives, expires, or changes ACL.

- [ ] **Step 5: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='ChunkingServiceTest,ProjectionOutboxTest' test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index apps/core-api/src/main/resources/db/migration apps/core-api/src/test/java/com/yunjihai/rag/retrieval/index
git commit -m "feat: create deterministic retrieval projection"
```

### Task 3: OpenSearch Index, Projector, and Rebuild

**Files:**
- Create: `infra/opensearch/chunks-v1.json`
- Create: `infra/opensearch/hybrid-search-pipeline-v1.json`
- Modify: `infra/compose/compose.yaml`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index/OpenSearchProjector.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index/RebuildIndexService.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/index/OpenSearchProjectionTest.java`

**Interfaces:**
- Consumes: outbox events and `RetrievalChunk` rows.
- Produces: alias `rag-chunks-read`, versioned physical index `rag-chunks-v1-*`, idempotent upsert/delete, and zero-downtime alias swap rebuild.

- [ ] **Step 1: Write Testcontainers projection tests**

Verify idempotent repeated upsert, delete on archive, ACL metadata update, superseded version removal, and full rebuild into an empty cluster.

- [ ] **Step 2: Run and verify failure**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchProjectionTest test`

- [ ] **Step 3: Define explicit mappings and hybrid pipeline**

Map Chinese analyzable text separately from keyword IDs, dates, booleans, principal arrays, dense vector dimensions supplied by embedding configuration, and nested source locator fields. Configure an RRF score-ranker search pipeline; do not use dynamic mapping for domain fields.

- [ ] **Step 4: Implement idempotent projector and alias rebuild**

Use chunk ID as OpenSearch document ID. A rebuild creates a new physical index, bulk loads PostgreSQL chunks, validates counts and sample queries, atomically swaps alias, and only then removes the previous physical index.

- [ ] **Step 5: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchProjectionTest test
git add infra/opensearch infra/compose apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index apps/core-api/src/test/java/com/yunjihai/rag/retrieval/index
git commit -m "feat: project published chunks into OpenSearch"
```

### Task 4: Hybrid Recall and Graceful Degradation

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/LexicalRetriever.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/VectorRetriever.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/HybridRecallService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/model/EmbeddingPort.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/search/HybridRecallServiceTest.java`

**Interfaces:**
- Consumes: `RetrievalQuery`, effective principal IDs, and embedding vector.
- Produces: rank-bearing lexical and vector candidate lists plus fused RRF candidates.

- [ ] **Step 1: Write ACL and degradation tests**

```java
@Test void lexicalResultsSurviveEmbeddingFailure() {
    embeddings.failWithTimeout();
    lexical.returns(CANDIDATE_A);
    assertThat(service.recall(query).candidates()).containsExactly(CANDIDATE_A);
}

@Test void everySubqueryCarriesAclAndPublicationFilters() {
    service.recall(queryFor(USER_A));
    assertThat(openSearch.lastQueries()).allSatisfy(q -> assertThat(q.filters())
        .contains("allowedPrincipalIds", "PUBLISHED", "effectiveFrom", "effectiveTo"));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=HybridRecallServiceTest test`

- [ ] **Step 3: Implement lexical/vector retrieval and RRF**

Run both branches concurrently with bounded timeouts. If one branch fails, return the healthy branch with a degradation flag; if both fail, throw a typed `RetrievalUnavailable`. Never retry a request after its caller deadline expires.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=HybridRecallServiceTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search apps/core-api/src/main/java/com/yunjihai/rag/model apps/core-api/src/test/java/com/yunjihai/rag/retrieval/search
git commit -m "feat: add permission-filtered hybrid recall"
```

### Task 5: Reranking, Diversity, and PostgreSQL Reauthorization

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/rank/RerankerPort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/rank/EvidenceSelectionService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/TrustedRetrievalService.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/TrustedRetrievalServiceTest.java`

**Interfaces:**
- Consumes: fused candidates and current PostgreSQL access decisions.
- Produces: final `EvidencePack` with at most the configured total and per-document cap.

- [ ] **Step 1: Write diversity and race tests**

```java
@Test void aclRevocationBetweenRecallAndAssemblyRemovesCandidate() {
    recall.returns(CANDIDATE_A);
    access.revokeBeforeRecheck(DOCUMENT_A);
    assertThat(service.retrieve(query).evidence()).isEmpty();
}

@Test void selectionDeduplicatesAndCapsOneDocument() {
    recall.returns(manyOverlappingChunks(DOCUMENT_A), strongChunk(DOCUMENT_B));
    assertThat(service.retrieve(query).evidence().stream().filter(e -> e.documentId().equals(DOCUMENT_A)).count())
        .isLessThanOrEqualTo(3);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=TrustedRetrievalServiceTest test`

- [ ] **Step 3: Implement reranking and selection**

Rerank up to 30 fused candidates. Remove near-duplicate text by normalized hash and similarity threshold, cap each document at three final chunks by default, and validate every surviving document/version through one batched PostgreSQL authorization query.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=TrustedRetrievalServiceTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval apps/core-api/src/test/java/com/yunjihai/rag/retrieval
git commit -m "feat: select diverse reauthorized evidence"
```

### Task 6: Citation APIs and Source Viewer

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/api/RetrievalController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/api/CitationController.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/api/CitationControllerTest.java`
- Create: `apps/web/src/api/retrieval.ts`
- Create: `apps/web/src/components/EvidenceList.vue`
- Create: `apps/web/src/components/SourceLocatorViewer.vue`
- Create: `apps/web/src/components/__tests__/SourceLocatorViewer.spec.ts`

**Interfaces:**
- Consumes: `EvidencePack` and authorized source-version access.
- Produces: `POST /api/v1/retrieval/search` and `GET /api/v1/citations/{evidenceId}` plus source-location UI.

- [ ] **Step 1: Write invalid-locator and revoked-access tests**

Reject a page locator without a page number, table locator without sheet/range, slide locator without slide number, and any citation whose source access was revoked after retrieval.

- [ ] **Step 2: Implement citation endpoint and UI**

Return evidence text, title, version, locator, and a short-lived authorized preview URL. The Vue viewer highlights a page bounding box, slide, sheet range, or Markdown heading; it never constructs a raw object URL client-side.

- [ ] **Step 3: Run API and UI tests**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=CitationControllerTest test
corepack pnpm --dir apps/web test --run SourceLocatorViewer
```

- [ ] **Step 4: Commit**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/api apps/core-api/src/test apps/web/src
git commit -m "feat: expose traceable source citations"
```

### Task 7: Versioned Offline Evaluation and Release Gate

**Files:**
- Create: `evaluation/retrieval/golden-v1.jsonl`
- Create: `evaluation/retrieval/README.md`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/evaluation/RetrievalEvaluationTest.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/evaluation/RetrievalMetrics.java`
- Create: `scripts/run-retrieval-eval.ps1`
- Create: `docs/runbooks/retrieval-quality.md`

**Interfaces:**
- Consumes: query, user fixture, relevant document/node IDs, forbidden document IDs, and expected locator types.
- Produces: JSON report with Recall@10, nDCG@10, citation-locator validity, unauthorized-hit count, configuration hash, and pass/fail.

- [ ] **Step 1: Add a minimal committed golden dataset and failing metric test**

```json
{"caseId":"case-001","query":"查找制造业客户的实施阶段","userFixture":"sales-a","relevantNodeIds":["node-a"],"forbiddenDocumentIds":["finance-secret"],"locatorType":"PAGE"}
```

The test fails when Recall@10 is below the checked-in baseline, any forbidden hit appears, or locator validation is below 100%.

- [ ] **Step 2: Implement deterministic evaluation runner**

Disable result caching, record model/index/pipeline/config hashes, compute metrics, and write a timestamped report outside source control plus a stable `latest.json` CI artifact.

- [ ] **Step 3: Add CI quality gate and run full phase verification**

```powershell
.\mvnw.cmd verify
corepack pnpm --dir apps/web test --run
powershell -ExecutionPolicy Bypass -File scripts/run-retrieval-eval.ps1
```

Expected: zero unauthorized hits; locator validity 100%; quality metrics meet the baseline stored in `evaluation/retrieval/README.md`.

- [ ] **Step 4: Commit and tag**

```powershell
git add evaluation scripts docs/runbooks apps infra
git commit -m "test: gate releases on trusted retrieval quality"
git tag pilot-retrieval-v1
```
