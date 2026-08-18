-- Spring Modulith's transactional outbox / event-publication registry (spring-modulith-events-jpa,
-- docs/adr/0005-kafka.md). Column set and constraint verified by letting Hibernate generate this
-- table against a scratch database and inspecting the result; types below diverge from Hibernate's
-- raw default (plain VARCHAR(255)) where that default is too small for real payloads --
-- serialized_event carries a full JSON-serialized event, including base64 file content for
-- DocumentUploadedEvent, so it needs TEXT rather than a 255-char cap.
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    listener_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date TIMESTAMPTZ,
    last_resubmission_date TIMESTAMPTZ,
    completion_attempts INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    CONSTRAINT event_publication_status_check
        CHECK (status IN ('PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED'))
);
