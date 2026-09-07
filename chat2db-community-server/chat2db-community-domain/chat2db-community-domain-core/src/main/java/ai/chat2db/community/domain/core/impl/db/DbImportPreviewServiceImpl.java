package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Database-independent import preview. CSV/XLS/XLSX are parsed through EasyExcel; the
 * preview reads only the first {@link #PREVIEW_ROW_LIMIT} rows and never writes.
 */
@Slf4j
@Service
public class DbImportPreviewServiceImpl implements IDbImportPreviewService {

    private static final int PREVIEW_ROW_LIMIT = 10;
    @Override
    public ImportPreview preview(Long dataSourceId, String databaseName, String schemaName,
                                 String tableName, File file) {
        List<Map<Integer, String>> rows = parseRows(file, PREVIEW_ROW_LIMIT);
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, String> header = rows.get(0);
        List<String> sourceNames = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = StringUtils.defaultIfBlank(header.get(i), "column_" + (i + 1));
            sourceNames.add(name);
        }

        List<List<String>> previewData = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> values = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < sourceNames.size(); columnIndex++) {
                values.add(StringUtils.defaultString(rows.get(rowIndex).get(columnIndex)));
            }
            previewData.add(values);
        }

        List<ImportPreview.TargetColumn> targetColumns = targetColumns(dataSourceId, databaseName, schemaName,
                tableName);
        List<ImportColumnMapping> suggested = new ArrayList<>();
        for (String source : sourceNames) {
            targetColumns.stream()
                    .filter(target -> StringUtils.equalsIgnoreCase(target.getName(), source))
                    .findFirst()
                    .map(target -> ImportColumnMapping.builder()
                            .sourceColumn(source)
                            .targetColumn(target.getName())
                            .build())
                    .ifPresent(suggested::add);
        }

        return ImportPreview.builder()
                .sourceColumns(sourceNames)
                .previewData(previewData)
                .targetColumns(targetColumns)
                .suggestedMapping(suggested)
                .previewLimit(PREVIEW_ROW_LIMIT)
                .build();
    }

    private static List<ImportPreview.TargetColumn> targetColumns(Long dataSourceId, String databaseName,
                                                                   String schemaName, String tableName) {
        TableMetadataRequest trustedRequest = TrustedMetadataRequestResolver.table(dataSourceId, databaseName,
                schemaName, tableName);
        Connection connection = Chat2DBContext.getConnection();
        return Chat2DBContext.getDbMetaData().columns(connection,
                        trustedRequest).stream()
                .map(column -> ImportPreview.TargetColumn.builder()
                        .name(column.getName())
                        .dataType(column.getColumnType())
                        .nullable(column.getNullable() != null && column.getNullable() == 1)
                        .autoIncrement(Boolean.TRUE.equals(column.getAutoIncrement()))
                        .defaultValue(column.getDefaultValue())
                        .comment(column.getComment())
                        .build())
                .toList();
    }

    /**
     * Parses the file with EasyExcel (same code path for preview and execution). The first
     * row is treated as the header; without a header the columns are named column_1..N.
     */
    private static List<Map<Integer, String>> parseRows(File file, int limit) {
        try {
            PreviewListener listener = new PreviewListener(limit);
            EasyExcel.read(file, listener).excelType(excelType(file)).sheet().headRowNumber(1).doRead();
            return listener.rows();
        } catch (Exception e) {
            log.warn("import preview parse failed for {}", file, e);
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
    }

    private static ExcelTypeEnum excelType(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return ExcelTypeEnum.CSV;
        }
        return name.endsWith(".xls") ? ExcelTypeEnum.XLS : ExcelTypeEnum.XLSX;
    }

    private static final class PreviewListener extends AnalysisEventListener<Map<Integer, String>> {
        private final int limit;
        private final List<Map<Integer, String>> rows = new ArrayList<>();

        private PreviewListener(int limit) {
            this.limit = limit;
        }

        @Override
        public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
            rows.add(normalize(headMap));
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            rows.add(normalize(data));
        }

        @Override
        public boolean hasNext(AnalysisContext context) {
            return rows.size() <= limit;
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // No resources to release; EasyExcel owns the stream.
        }

        private List<Map<Integer, String>> rows() {
            return rows;
        }

        private static Map<Integer, String> normalize(Map<Integer, String> values) {
            Map<Integer, String> normalized = new LinkedHashMap<>();
            if (values != null) {
                values.forEach((index, value) -> normalized.put(index, value == null ? "" : value));
            }
            return normalized;
        }
    }
}
