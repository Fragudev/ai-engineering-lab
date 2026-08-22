package io.github.fragudev.ailab;

import io.github.fragudev.ailab.rag.RagPipeline;
import io.github.fragudev.ailab.rag.RagProfile;
import io.github.fragudev.ailab.rag.RagProfiles;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class RetrievalController {

    private final RagPipeline ragPipeline;

    RetrievalController(RagPipeline ragPipeline) {
        this.ragPipeline = ragPipeline;
    }

    @PostMapping("/retrieval:search")
    RetrievalTraceResponse search(@Valid @RequestBody RetrievalSearchRequest request) {
        RagProfile profile = resolveProfile(request.ragProfile());
        return RetrievalTraceResponse.from(ragPipeline.search(request.query(), profile));
    }

    @GetMapping("/rag/profiles")
    List<RagProfileResponse> listProfiles() {
        return RagProfiles.all().stream().map(RagProfileResponse::from).toList();
    }

    static RagProfile resolveProfile(String name) {
        return RagProfiles.byName(name).orElseThrow(() -> new IllegalArgumentException("Unknown ragProfile: " + name));
    }
}
