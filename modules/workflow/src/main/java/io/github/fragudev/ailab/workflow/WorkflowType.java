package io.github.fragudev.ailab.workflow;

/**
 * The set of workflow types this engine knows how to run. One value today — the roadmap's own
 * "One workflow" framing (docs/roadmap.md, Phase 6) — mapped to/from the {@code {type}} segment of
 * {@code POST /api/v1/workflows/{type}/runs}.
 */
public enum WorkflowType {
    DOCUMENTATION_RESEARCH("documentation-research");

    private final String slug;

    WorkflowType(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    public static WorkflowType fromSlug(String slug) {
        for (WorkflowType type : values()) {
            if (type.slug.equals(slug)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown workflow type: '%s'".formatted(slug));
    }
}
