package ai.chat2db.community.domain.api.model.db;

import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportPreview {

    private List<String> sourceColumns;

    private List<List<String>> previewData;

    private String targetTableName;

    private List<TargetColumn> targetColumns;

    private List<ImportColumnMapping> suggestedMapping;

    private int previewLimit;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetColumn {

        private String name;

        private String dataType;

        private boolean nullable;

        private boolean autoIncrement;

        private String defaultValue;

        private String comment;
    }
}
