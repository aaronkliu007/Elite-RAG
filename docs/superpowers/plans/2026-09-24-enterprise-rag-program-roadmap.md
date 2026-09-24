# Enterprise RAG Delivery Program Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans on each linked implementation plan. This roadmap is an index, not an executable task list.

**Goal:** Deliver the approved enterprise RAG solution-generation pilot through four independently reviewable and releasable increments.

**Architecture:** A Vue 3 web client calls a Java 21/Spring Boot modular monolith. PostgreSQL is the source of truth, MinIO stores immutable source files, OpenSearch is a rebuildable retrieval projection, and a stateless Python worker wraps document parsing/OCR. All cloud-model traffic exits through the Spring Boot model gateway.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, Maven, PostgreSQL, Flyway, MinIO, OpenSearch, Python, FastAPI, Docling, PaddleOCR, Vue 3, TypeScript, Vite, Vitest, Playwright, Docker.

**Spec:** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## Global Constraints

- Original documents, parsed content, indexes, permissions, solutions, and audit records remain on the company network.
- Only the model gateway may reach approved cloud-model APIs.
- Java/Spring Boot owns all business rules and AI orchestration; Python is a stateless parsing/OCR/model-compute worker only.
- PostgreSQL is authoritative; OpenSearch is rebuildable and must never become the permission source of truth.
- Authorization is default-deny allow-listing; effective access is knowledge-space grant intersected with document grant and active publication state.
- Search performs ACL filtering before retrieval and PostgreSQL authorization again before returning evidence.
- Production retrieval uses only the current published document version.
- Requirement confirmation and outline confirmation are mandatory human gates.
- Evidence-backed claims carry citations; insufficient evidence produces an explicit refusal or human-confirmation marker.
- Every release passes permission and retrieval-quality regression suites.

## Locked Repository Structure

```text
apps/
  core-api/                 Spring Boot modular monolith
  web/                      Vue 3 solution and knowledge-management UI
workers/
  document-parser/          Stateless FastAPI worker for Docling/OCR
contracts/
  parser/                   Versioned JSON Schemas shared with the worker
infra/
  compose/                  Local PostgreSQL, MinIO, OpenSearch, mock-model stack
  opensearch/               Index templates and search pipelines
docs/
  adr/                      Architecture decisions
  runbooks/                 Operator and recovery procedures
  superpowers/specs/        Approved design
  superpowers/plans/        Executable phase plans
pom.xml                     Maven reactor root
pnpm-workspace.yaml         Frontend workspace declaration
```

## Execution Order and Gates

1. [`2026-09-24-knowledge-foundation.md`](2026-09-24-knowledge-foundation.md)
   - Delivers DingTalk-ready identity boundaries, ACL, immutable documents, parsing, cataloging, publishing, audit, and a minimal web UI.
   - Exit gate: an authorized user can publish and view a parsed document; an unauthorized user receives no metadata, file, or parsed content.
2. [`2026-09-24-trusted-retrieval.md`](2026-09-24-trusted-retrieval.md)
   - Delivers OpenSearch projection, hybrid retrieval, RRF, reranking, citations, ACL double-checking, and offline evaluation.
   - Exit gate: permission suite is 100% green and the approved retrieval/citation baseline is met.
3. [`2026-09-24-solution-workbench.md`](2026-09-24-solution-workbench.md)
   - Delivers requirement confirmation, template-driven outlines, per-section generation, citation verification, editing, versioning, Word export, and feedback.
   - Exit gate: a pilot user completes the full requirement-to-Word workflow with traceable evidence.
4. [`2026-09-24-pilot-hardening.md`](2026-09-24-pilot-hardening.md)
   - Delivers security controls, observability, recovery, load testing, operational runbooks, and pilot acceptance reporting.
   - Exit gate: security, recovery, performance, and business acceptance checks all pass.

## Dependency Rules

- A later phase consumes only interfaces explicitly produced by earlier phase plans.
- No later-phase feature may be pulled into an earlier phase to make a demo look complete.
- If an earlier interface must change, update its plan contract, add a compatibility test, and commit that change before consuming it.
- Each phase gets a fresh reviewer gate and a releasable tag before the next phase starts.

## Recommended Tags

- `pilot-foundation-v1`
- `pilot-retrieval-v1`
- `pilot-workbench-v1`
- `pilot-ready-v1`
