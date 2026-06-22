# Retrieval Optimization Phase 2

## What Changed

This phase adds an optional Elasticsearch/OpenSearch BM25 candidate source.

Added:

- `EventSearchIndexService`
- OpenSearch service in `docker-compose.yml`
- Search config in `application.yml`
- Search index rebuild API: `POST /api/ai/retrieval/reindex`

The feature is disabled by default, so the existing MySQL + Java retrieval still works without OpenSearch.

## Enable Search Engine Retrieval

Set these values in `.env`:

```properties
SEARCH_ENABLED=true
SEARCH_BASE_URL=http://localhost:9200
SEARCH_INDEX_NAME=do_not_miss_events
```

Start infrastructure:

```powershell
docker compose up -d
```

Then restart Spring Boot and rebuild the event index:

```http
POST /api/ai/retrieval/reindex
Authorization: Bearer <token>
```

## How It Works

When `SEARCH_ENABLED=true`:

1. Query Rewrite produces a richer rewritten query.
2. `EventSearchIndexService` sends the rewritten query to OpenSearch.
3. OpenSearch returns BM25-ranked event IDs.
4. `HybridEventRetrievalService` loads those events from MySQL.
5. Existing Java scoring still computes:
   - keyword score
   - intent score
   - memory score
   - metadata score
6. LLM rerank still happens after candidate retrieval.

When OpenSearch is disabled or fails:

1. The system falls back to the original MySQL candidate retrieval.
2. No user-facing feature should break.

## Current Boundary

Implemented:

- Query Rewrite
- Retrieval trace
- Retrieval evaluation set
- Search-engine BM25 candidate source

Not yet implemented:

- Embedding generation
- Vector index field
- BM25 + vector fusion
- Dedicated reranker model
- Context compression beyond compact memory prompt text

## Next Phase

Add embedding/vector retrieval:

1. Add embedding client.
2. Store event embedding in OpenSearch `knn_vector` or `dense_vector`.
3. Generate query embedding after Query Rewrite.
4. Retrieve BM25 candidates and vector candidates independently.
5. Fuse them with RRF.
6. Compare metrics using `/api/ai/retrieval/evaluation`.
