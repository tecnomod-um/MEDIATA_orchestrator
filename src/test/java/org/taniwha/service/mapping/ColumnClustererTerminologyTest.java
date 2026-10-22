package org.taniwha.service.mapping;

import org.junit.jupiter.api.Test;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.model.ColCluster;
import org.taniwha.model.ColStats;
import org.taniwha.model.EmbeddedColumn;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnClustererTerminologyTest {
    private final ColumnClusterer clusterer = new ColumnClusterer(new MappingServiceSettings(
            60, 120, 0.33, 0.56, 6, 40, 10, 0.22, 4, 3, 2, 6));

    @Test
    void exactSnomedIdJoinsDifferentlyNamedCompatibleColumns() {
        assertThat(clusterer.clusterColumns(List.of(
                column("Activities", "123456789", 0),
                column("FunctionalAbilities", "123456789", 1)
        ))).hasSize(1);
    }

    @Test
    void differentOrAbsentIdsDoNotForceAJoin() {
        assertThat(clusterer.clusterColumns(List.of(
                column("Activities", "123456789", 0),
                column("FunctionalAbilities", "987654321", 1)
        ))).hasSize(2);
        assertThat(clusterer.clusterColumns(List.of(
                column("Activities", "", 0),
                column("FunctionalAbilities", "", 1)
        ))).hasSize(2);
    }

    @Test
    void sameIdDoesNotMergeItemAndTotalScore() {
        assertThat(clusterer.clusterColumns(List.of(
                column("BarthelToilet", "123456789", 0, List.of("integer", "min:0", "max:10")),
                column("BarthelTotal", "123456789", 1, List.of("integer", "min:0", "max:100"))
        ))).hasSize(2);
    }

    private EmbeddedColumn column(String name, String id, int vectorIndex) {
        return column(name, id, vectorIndex, List.of("dependent", "independent"));
    }

    private EmbeddedColumn column(String name, String id, int vectorIndex, List<String> values) {
        float[] vector = new float[2];
        vector[vectorIndex] = 1.0f;
        ColStats stats = org.taniwha.util.ParseUtil.parseColStats(values);
        return new EmbeddedColumn("node", name + ".csv", name, name, values, vector,
                null, stats, id);
    }
}
