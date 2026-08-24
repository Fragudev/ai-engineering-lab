package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.workflow.WorkflowRunStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

/** A hand-written in-memory fake {@link WorkflowRunRepository} — no mocking framework in this
 * codebase (AGENTS.md, matching {@code FakeWorkflowStepRepository}'s own style). {@code
 * findByStatusIn} re-queries the live {@code status} field on every call, exactly like the real
 * JPA query would — the property {@link WorkflowResumerTest} relies on to prove {@code resumeAll()}
 * never resubmits a run that has since gone terminal. */
class FakeWorkflowRunRepository implements WorkflowRunRepository {

    private final Map<UUID, WorkflowRun> runs = new LinkedHashMap<>();

    @Override
    public <S extends WorkflowRun> S save(S entity) {
        runs.put(entity.id().value(), entity);
        return entity;
    }

    @Override
    public List<WorkflowRun> findByStatusIn(List<WorkflowRunStatus> statuses) {
        return runs.values().stream()
                .filter(run -> statuses.contains(run.status()))
                .toList();
    }

    @Override
    public Optional<WorkflowRun> findById(UUID id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public List<WorkflowRun> findAll() {
        return new ArrayList<>(runs.values());
    }

    @Override
    public boolean existsById(UUID id) {
        return runs.containsKey(id);
    }

    @Override
    public long count() {
        return runs.size();
    }

    @Override
    public void deleteById(UUID id) {
        runs.remove(id);
    }

    @Override
    public void delete(WorkflowRun entity) {
        runs.remove(entity.id().value());
    }

    @Override
    public void deleteAll() {
        runs.clear();
    }

    // Everything below is unexercised by any test — throwing rather than silently returning an
    // empty/wrong result means a future test that starts relying on one of these fails loudly
    // instead of passing against fake behaviour nobody implemented for real.

    @Override
    public <S extends WorkflowRun> List<S> saveAll(Iterable<S> entities) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public List<WorkflowRun> findAllById(Iterable<UUID> ids) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAll(Iterable<? extends WorkflowRun> entities) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> S saveAndFlush(S entity) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> List<S> saveAllAndFlush(Iterable<S> entities) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAllInBatch(Iterable<WorkflowRun> entities) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<UUID> ids) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAllInBatch() {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public WorkflowRun getOne(UUID id) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public WorkflowRun getById(UUID id) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public WorkflowRun getReferenceById(UUID id) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public List<WorkflowRun> findAll(Sort sort) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public org.springframework.data.domain.Page<WorkflowRun> findAll(Pageable pageable) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> org.springframework.data.domain.Page<S> findAll(
            Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> long count(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowRun, R> R findBy(
            Example<S> example, java.util.function.Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException("not exercised by this test");
    }
}
