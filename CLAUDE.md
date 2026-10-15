# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Ragent is a production-grade **Agentic RAG** platform in Java — a modular monolith covering document ingestion → hybrid retrieval → question answering, plus a ReAct agent layer with MCP tool use, skills, memory, and human confirmation. It deliberately avoids Spring AI / LangChain4j and implements the RAG/Agent pipeline itself. Comments, commit messages, and the README are written in Chinese; keep new comments in Chinese to match.

- Backend: Java 17, Spring Boot 4.1.0, Maven multi-module (7 modules), Maven Wrapper (`./mvnw`).
- Frontend: React 18 + Vite + TypeScript + Tailwind (in `frontend/`).
- Infra dependencies: PostgreSQL (MyBatis-Plus), Redis (Redisson + Sa-Token), RocketMQ, Milvus or pgvector, MinIO/S3/OSS object storage, optional Elasticsearch + LightRAG.

## Build, Test, Lint

Run Maven from the repo root. Use `./mvnw` (wrapper) rather than a system `mvn`.

```bash
./mvnw clean package -DskipTests     # build all modules
./mvnw compile                        # compile; Spotless auto-applies format + license header on the compile phase
./mvnw test                           # run all unit tests
./mvnw test -pl rag -Dtest=MessageSummaryTest          # single test class in one module
./mvnw test -pl rag -Dtest=MessageSummaryTest#method   # single test method
./mvnw spotless:check                 # verify formatting (apply is already bound to compile)
```

- Tests are plain unit tests using Mockito (the `-javaagent` for Mockito is wired via Surefire `argLine` in the root `pom.xml`). Test code lives under each module's `src/test`. `rag` and `agent` hold the bulk of the coverage.
- **Spotless** runs `apply` automatically at the `compile` phase and injects the Apache license header from `resources/format/copyright.txt` — do not add license headers by hand; just compile and it will normalize.

Frontend (from `frontend/`):

```bash
npm install
npm run dev        # Vite on http://127.0.0.1:5173, proxies /api -> http://localhost:9090
npm run build
npm run lint       # eslint --max-warnings 0
npm run format     # prettier --write .
```

## Running

Two Spring Boot applications exist (both use `spring-boot-maven-plugin`):

```bash
./mvnw -pl bootstrap spring-boot:run    # main app: http://localhost:9090, context-path /api/ragent
./mvnw -pl mcp-server spring-boot:run   # standalone MCP tool server: http://127.0.0.1:9099
```

The main app needs PostgreSQL, Redis, RocketMQ, and object storage to fully start (see `bootstrap/src/main/resources/application.yaml`). Docker Compose stacks for these live in `resources/docker/` (Milvus, RocketMQ, Langfuse, LightRAG/Neo4j, Elasticsearch). Database schema/init/upgrade SQL is in `resources/database/`.

## Architecture

Seven Maven modules form a front/back-separated modular monolith. Boundaries are designed so swapping a model provider, vector store, or object storage does not touch the QA orchestration:

| Module | Responsibility |
|:---|:---|
| `infra-ai` | Model clients (`ChatClient` / `EmbeddingClient` / `RerankClient` / VLM), model tiers, candidate routing, first-packet probe, health state, and circuit-breaker fallback |
| `system` | User auth (Sa-Token), audit log, and sample endpoints — the system-support domain below the business engines |
| `rag` | RAG QA (v1 workflow), knowledge base, ingestion pipeline, intent tree, retrieval, sessions, and admin APIs |
| `agent` | AgentScope ReAct execution engine (v2), MCP tool discovery/calls, skills, memory, and human confirmation |
| `framework` | Cross-cutting infra: unified response/exception, idempotency, distributed ID, MQ adapter, SSE + cross-node stream cancellation, trace |
| `mcp-server` | Standalone example MCP tool service (weather/ticket/sales/web-search) and the "比特严选" (Bit Selection) e-commerce demo tools |
| `bootstrap` | Assembly only — `RagentApplication` (main class) and `application.yaml` |

### Two coexisting execution paths

`ragent.engine.type` (in `application.yaml`) selects the architecture — both ship:

- **`workflow` (v1)** — a fixed, code-orchestrated pipeline in `rag`: query rewrite → intent tree / knowledge-base routing → parallel retrieval → RRF fusion → rerank → prompt assembly → streaming generation.
- **`agent` (v2)** — ReAct loop in `agent`: RAG becomes one tool among many; the model decides what to retrieve, which MCP tools to call, and when to stop. MCP client wiring lives only in `agent` (servers configured under `agent.mcp.servers`).

### Key subsystems

- **Model access (`infra-ai`)**: provider adapters (Bailian/Ollama/DeepSeek/AIHubMix/SiliconFlow) implement a common client interface. `model/` (`ModelSelector`, `ModelRoutingExecutor`, `ModelHealthStore`, `ModelCaller`) implements tier selection, multi-candidate failover, first-packet probing, and three-state circuit breaking. Providers, candidates, tiers, and timeouts are declared in `ai.*` in `application.yaml`.
- **Retrieval funnel (`rag/core/retrieval`)**: vector / keyword / graph / web-search channels run in parallel under a recall budget, then RRF fusion, then rerank, then a final top-k and an evidence relevance gate. New channels implement `SearchChannel`; post-processors form a responsibility chain.
- **Ingestion pipeline (`rag/ingestion`, `rag/core/parser`, `rag/core/ingest`)**: configurable node pipeline (parse via Tika/MinerU → chunk → embed → sink). Vector writes are decorated to also write keyword/graph indexes. Document refresh uses a state machine + distributed lock.
- **Agent memory (`agent/memory`, `agent/state`)**: three-layer context management — recent N turns, on-demand trim/compaction at a char watermark, and a persistent summary; long-term memory is extracted and consolidated across sessions into PostgreSQL (`AgentMemory*` tables, `PgAgentStateStore` for run state). Middlewares (`AgentContextCompactionMiddleware`, `AgentUserMemoryMiddleware`, `AgentSkillMaskingMiddleware`, confirm middleware) are pluggable around the ReAct loop.
- **Cross-cutting (`framework`)**: `Result`/`BaseErrorCode`/exception hierarchy, `@IdempotentSubmit`/`@IdempotentConsume` aspects, Snowflake ID, RocketMQ transaction-message producer adapter, SSE output with `TaskCancellation` for cross-node cancel, and `RagTrace` for per-run/node trace.

## Conventions & gotchas

- **Config single source of truth**: `bootstrap/src/main/resources/application.yaml` is the authoritative config. Module tests import it (see `rag/src/test/resources/application.yaml`), so don't duplicate backend config elsewhere. `mcp-server` has its own small `application.yml`.
- **MyBatis mappers**: all `*Mapper` interfaces are registered via `@MapperScan` in `RagentApplication` — add new mapper packages there when creating a module.
- **PostgreSQL `jsonb` columns** use the shared `JsonbTypeHandler` / `MyMetaObjectHandler` from `framework`.
- **Spring Boot 4** ships Jackson 3; the repo enables `spring-boot-jackson2` compatibility because Ragent and the MCP SDK still use the Jackson 2 API. Don't drop that dependency.
- **Demo domain**: "比特严选" (Bit Selection) is the built-in e-commerce demo scenario. Its seed data, prompts, and CLI initializers live under `resources/initializer/bit-selection`; `mcp-server` serves its business tools from a separate `ragent_bit` database.
- **Regression suites** under `resources/regression/` are live-service evaluation harnesses (Java CLIs), not unit tests; they assume a running stack and share HTTP/JDBC clients from `resources/initializer/common`.
