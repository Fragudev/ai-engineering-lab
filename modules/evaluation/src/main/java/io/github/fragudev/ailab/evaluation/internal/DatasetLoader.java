package io.github.fragudev.ailab.evaluation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.fragudev.ailab.evaluation.EvalCase;
import io.github.fragudev.ailab.evaluation.EvalCaseCategory;
import io.github.fragudev.ailab.evaluation.EvalDataset;
import io.github.fragudev.ailab.shared.EvalCaseId;
import io.github.fragudev.ailab.shared.EvalDatasetId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Loads a golden dataset from an {@code eval/dataset/*.yaml} file, upserting by
 * {@code (name, version)} so re-running against an already-loaded dataset is a no-op rather than a
 * duplicate. A private ObjectMapper — same reasoning as {@code knowledge.internal.JsonMetadataConverter}
 * for not depending on which Jackson generation the rest of the app happens to wire. */
@Component
public class DatasetLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final EvalDatasetRepository datasetRepository;
    private final EvalCaseRepository caseRepository;

    public DatasetLoader(EvalDatasetRepository datasetRepository, EvalCaseRepository caseRepository) {
        this.datasetRepository = datasetRepository;
        this.caseRepository = caseRepository;
    }

    public EvalDataset load(Path yamlPath) {
        EvalDatasetYaml parsed;
        try (InputStream in = Files.newInputStream(yamlPath)) {
            parsed = YAML_MAPPER.readValue(in, EvalDatasetYaml.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load eval dataset from " + yamlPath, e);
        }

        EvalDataset dataset = datasetRepository
                .findByNameAndVersion(parsed.name(), parsed.version())
                .orElseGet(() -> datasetRepository.save(
                        new EvalDataset(EvalDatasetId.generate(), parsed.name(), parsed.version())));

        for (EvalCaseYaml caseYaml : parsed.cases()) {
            if (caseRepository
                    .findByDatasetIdAndCaseKey(dataset.id().value(), caseYaml.key())
                    .isPresent()) {
                continue;
            }
            caseRepository.save(new EvalCase(
                    EvalCaseId.generate(),
                    dataset.id(),
                    caseYaml.key(),
                    caseYaml.question(),
                    caseYaml.expectedAnswer(),
                    caseYaml.goldChunkRefs().toArray(new String[0]),
                    caseYaml.tags().toArray(new String[0]),
                    EvalCaseCategory.valueOf(caseYaml.category())));
        }

        return dataset;
    }

    public List<EvalCase> casesFor(EvalDataset dataset) {
        return caseRepository.findByDatasetId(dataset.id().value());
    }

    record EvalDatasetYaml(String name, String version, List<EvalCaseYaml> cases) {}

    record EvalCaseYaml(
            String key,
            String question,
            String expectedAnswer,
            List<String> goldChunkRefs,
            List<String> tags,
            String category) {}
}
