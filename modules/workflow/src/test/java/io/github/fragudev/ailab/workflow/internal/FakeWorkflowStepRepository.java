package io.github.fragudev.ailab.workflow.internal;

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

/** A hand-written in-memory fake {@link WorkflowStepRepository} — no mocking framework in this
 * codebase (AGENTS.md, matching {@code FakeChatProvider}'s own style). {@link StageRunner} only
 * ever calls {@link #save}; every other {@code JpaRepository} method exists solely because the
 * interface requires it and throws if a future test starts relying on it, the same defensive
 * pattern {@code FakeChatProvider}'s unexercised methods already use. */
class FakeWorkflowStepRepository implements WorkflowStepRepository {

    private final Map<UUID, WorkflowStep> steps = new LinkedHashMap<>();

    @Override
    public <S extends WorkflowStep> S save(S entity) {
        steps.put(entity.id().value(), entity);
        return entity;
    }

    @Override
    public List<WorkflowStep> findByRunIdOrderByStepIndexAsc(UUID runId) {
        return steps.values().stream()
                .filter(step -> step.runId().value().equals(runId))
                .sorted((a, b) -> Integer.compare(a.stepIndex(), b.stepIndex()))
                .toList();
    }

    @Override
    public List<WorkflowStep> findAll() {
        return new ArrayList<>(steps.values());
    }

    @Override
    public Optional<WorkflowStep> findById(UUID id) {
        return Optional.ofNullable(steps.get(id));
    }

    @Override
    public boolean existsById(UUID id) {
        return steps.containsKey(id);
    }

    @Override
    public long count() {
        return steps.size();
    }

    @Override
    public void deleteById(UUID id) {
        steps.remove(id);
    }

    @Override
    public void delete(WorkflowStep entity) {
        steps.remove(entity.id().value());
    }

    @Override
    public void deleteAll() {
        steps.clear();
    }

    // Everything below is unexercised by any test — throwing rather than silently returning an
    // empty/wrong result means a future test that starts relying on one of these fails loudly
    // instead of passing against fake behaviour nobody implemented for real.

    @Override
    public <S extends WorkflowStep> List<S> saveAll(Iterable<S> entities) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public List<WorkflowStep> findAllById(Iterable<UUID> ids) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> ids) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAll(Iterable<? extends WorkflowStep> entities) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> S saveAndFlush(S entity) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> List<S> saveAllAndFlush(Iterable<S> entities) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void deleteAllInBatch(Iterable<WorkflowStep> entities) {
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
    public WorkflowStep getOne(UUID id) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public WorkflowStep getById(UUID id) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public WorkflowStep getReferenceById(UUID id) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public List<WorkflowStep> findAll(Sort sort) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public org.springframework.data.domain.Page<WorkflowStep> findAll(Pageable pageable) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> org.springframework.data.domain.Page<S> findAll(
            Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> long count(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public <S extends WorkflowStep, R> R findBy(
            Example<S> example, java.util.function.Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException("not exercised by this test");
    }
}
