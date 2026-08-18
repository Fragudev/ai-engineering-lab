# Operations runbook

What to look at when something is wrong. Written for a local deployment, but structured the way a
real runbook is — symptom, likely cause, where to look — because the discipline is the same.

**Status:** design. Populated as each phase lands; commands below are the intended interface.

---

## Services

| Service | URL | Purpose |
|---|---|---|
| Application | http://localhost:8080 | API and UI |
| OpenAPI | http://localhost:8080/swagger-ui | Interactive API documentation |
| Grafana | http://localhost:3000 | Dashboards, traces, logs |
| Kafka UI | http://localhost:8081 | Topics, consumer groups, dead-letter inspection |
| PostgreSQL | localhost:5432 | Database |
| LM Studio | localhost:1234 | Model server, **on the host** |

---

## Health checks

```bash
curl -s localhost:8080/actuator/health | jq        # app, database, Kafka, model server
curl -s localhost:1234/v1/models | jq '.data[].id' # loaded models
./scripts/bootstrap.sh --check-only                # full prerequisite verification
```

`bootstrap.sh` verifies that LM Studio responds, that a chat model and an embedding model are
loaded, and that the embedding dimensions match the schema. Run it first when anything behaves
strangely — a dimension mismatch produces symptoms that look nothing like their cause.

---

## Symptoms

### Ingestion job stuck in a stage

Look at consumer lag in Kafka UI, then the ingestion dashboard in Grafana.

- **Lag growing, no errors** → the embedding stage is the bottleneck. Expected on a small model; check
  `ingestion_jobs_active` and GPU utilisation on the host.
- **Lag flat, job not advancing** → the consumer is likely dead. Check application logs for the
  consumer group.
- **Messages in the `.dlt` topic** → retries were exhausted. Inspect the message headers in Kafka UI
  for the original exception, and `ingestion_job.last_error` for the persisted reason.

### Answers with no citations

- **Retrieval returned nothing** → check `retrieval_score_distribution`. If everything falls below
  threshold, either the corpus lacks the content or the similarity threshold is too aggressive.
- **Retrieval returned chunks but the answer cites none** → a generation or citation-extraction
  problem, not retrieval. Use `POST /api/v1/retrieval:search` with the same query to confirm what
  was available.
- **Empty index** → `select count(*) from chunk;`. If zero, run `./scripts/seed.sh`.

### Slow or hanging responses

Open the trace in Grafana and read the stage spans. The time is in one of four places: embedding,
retrieval, reranking, or generation.

- **Generation dominates** → normal. It is the model, not the system.
- **Retrieval dominates** → check that the HNSW index exists and is being used
  (`explain analyze` the query). A sequential scan over vectors is the usual cause.
- **Embedding dominates** → LM Studio is loading the model, or the chat model evicted the embedding
  model from memory. Keep both resident.
- **Everything slow** → check the circuit breaker state; the model server may be failing and
  retrying.

### Dimension mismatch on startup

The application refuses to start when the loaded embedding model's dimensions differ from the
schema's 1024.

```bash
./scripts/bootstrap.sh --check-only     # confirms the mismatch
# load bge-m3 in LM Studio, then:
./scripts/reindex.sh                    # only if the model genuinely changed
```

Reindexing re-embeds every chunk. It is expensive and it is why the embedding model is a fixed
project-wide decision rather than a configuration knob.

### CI passing locally but failing on push

CI uses the `recorded` provider. If a change altered a prompt or a request shape, the fixtures are
stale.

```bash
./scripts/record-fixtures.sh    # re-record against live LM Studio
```

Review the fixture diff before committing. A fixture that changed unexpectedly is telling you
something.

---

## Useful queries

```sql
-- documents by ingestion status
select status, count(*) from document group by status;

-- jobs that failed and why
select d.title, j.stage, j.attempts, j.last_error
from ingestion_job j join document d on d.id = j.document_id
where j.stage = 'FAILED' order by j.updated_at desc;

-- token cost by day
select date_trunc('day', created_at) as day,
       sum(prompt_tokens + completion_tokens) as tokens
from message group by 1 order by 1 desc;

-- chunk distribution, to spot chunking problems
select document_id, count(*) chunks, avg(token_count)::int avg_tokens
from chunk group by 1 order by 2 desc limit 20;

-- is the HNSW index being used?
explain analyze
select id from chunk order by embedding <=> '[...]'::vector limit 10;
```

---

## Reset

```bash
# wipe application data, keep containers
docker compose -f infrastructure/docker-compose.yml down -v
docker compose -f infrastructure/docker-compose.yml up -d
./scripts/seed.sh

# rebuild the index without touching documents
./scripts/reindex.sh
```
