# Do Not Miss

Do Not Miss is a student growth planning platform built around activities, personal challenges, schedules, AI recommendation, long-term memory, and achievement evidence.

The project started from a real observation: students often miss social practice opportunities because information is scattered and passive. This system connects activity discovery, AI planning, personal achievement records, ability tags, schedule management, and AI Coach into one growth loop.

## Features

- Student and organization-side activity flow
- Activity search and AI event recommendation
- Query Rewrite with multi-turn search context
- OpenSearch BM25 + Embedding hybrid retrieval
- Multi-Agent plan recommendation with schedule checking and critic review
- One-click AI plan import into Schedule
- Personal challenges and achievement records
- LLM-based user profile snapshot
- LLM-based growth tag extraction and milestone timeline
- AI Coach with long-term memory review
- RabbitMQ async tasks for indexing, growth tags, and user profile refresh
- Outbox Pattern for reliable message delivery
- Agent Run / Step logs for observability

## Project Structure

```text
do-not-miss
├── backend/   Spring Boot backend, MySQL, Redis, RabbitMQ, OpenSearch, AI logic
├── frontend/  Static frontend page: HTML, CSS, JavaScript
└── docs/      Architecture diagram, interview notes, backlog, problem records
```

## Tech Stack

- Backend: Java 21, Spring Boot, Spring Data JPA, Flyway
- Database: MySQL
- Cache / session: Redis
- Message queue: RabbitMQ
- Search: OpenSearch, BM25, vector retrieval
- AI: Qwen / OpenAI-compatible LLM API, Embedding API
- Frontend: HTML, CSS, JavaScript
- Deployment: Docker Compose for middleware, Maven for backend

## Quick Start

### 1. Start middleware

```powershell
cd backend
docker compose up -d
```

Required services:

- MySQL: `3306`
- Redis: `6379`
- RabbitMQ: `5672`, management UI `15672`
- OpenSearch: `9200`

### 2. Configure backend environment

Copy the example file:

```powershell
cd backend
copy .env.example .env
```

Fill your own AI API key in `.env`:

```text
DASHSCOPE_API_KEY=your-api-key
```

Do not commit `.env` to GitHub.

### 3. Run backend

```powershell
cd backend
mvn spring-boot:run
```

Backend runs at:

```text
http://localhost:8080
```

### 4. Open frontend

Open:

```text
frontend/index.html
```

The static frontend calls the backend API at `http://localhost:8080`.

## GitHub Notes

Before uploading:

- Make sure `backend/.env` is not committed.
- Make sure `backend/target/` is not committed.
- Keep `.env.example` because it documents required configuration.
- If using a real AI key, rotate it if it was ever pasted into a public place.

## Interview Highlights

- AI recommendation is constrained by real database candidates and eventId validation to reduce hallucination.
- OpenSearch is used as a derived search index, while MySQL remains the source of truth.
- Outbox Pattern bridges MySQL transactions and RabbitMQ delivery.
- Consumers are designed around idempotent upsert / refresh semantics.
- Agent execution is split into observable steps for debugging bad cases.
- User profile and growth tags are treated as derived data and refreshed asynchronously.
