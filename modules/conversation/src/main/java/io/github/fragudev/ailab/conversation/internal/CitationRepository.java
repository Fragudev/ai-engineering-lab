package io.github.fragudev.ailab.conversation.internal;

import io.github.fragudev.ailab.conversation.Citation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CitationRepository extends JpaRepository<Citation, UUID> {

    List<Citation> findByMessageIdOrderByOrdinalAsc(UUID messageId);
}
