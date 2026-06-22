# Retrieval Optimization Phase 1

## What Changed

This phase makes retrieval observable and testable before introducing Elasticsearch or vector search.

Added:

- Query Rewrite: `QueryRewriteService`
- Retrieval Trace: `POST /api/ai/retrieval/trace`
- Retrieval Evaluation: `GET /api/ai/retrieval/evaluation`
- Evaluation set: `src/main/resources/retrieval-eval-set.json`

## Current Pipeline

1. User submits natural-language need.
2. Query Rewrite converts it into structured retrieval hints:
   - goal
   - level
   - intent tags
   - skills
   - preferred categories
   - preferred location
   - benefit preference
   - constraints
3. Hybrid retrieval scores candidate events:
   - keyword score
   - intent score
   - memory score
   - metadata score
4. Top candidates are sent to the LLM for reranking and explanation.
5. Backend validates that every recommended `eventId` comes from real candidate events.

## Trace API

Request:

```http
POST /api/ai/retrieval/trace
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "need": "我现在有点想去日本留学，想从零开始学日语",
  "limit": 10
}
```

Response includes:

- rewritten query
- structured query understanding
- compressed user memory
- MCP tool context
- per-event retrieval scores
- retrieval evidence

## Evaluation API

Request:

```http
GET /api/ai/retrieval/evaluation
Authorization: Bearer <token>
```

Response includes:

- hit rate
- average precision@K
- per-case expected terms
- matched terms
- top candidates
- query rewrite result

## Next Phase

Recommended order:

1. Add Elasticsearch/OpenSearch index for event BM25.
2. Sync events to search index when created/updated/deleted.
3. Add embedding generation for events and queries.
4. Add BM25 + vector hybrid retrieval with RRF fusion.
5. Add reranker stage for top 20-50 candidates.
6. Expand evaluation set and compare old/new retrieval metrics.
