package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.db.ImportTargetColumn;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
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
        requireUniqueSourceColumns(sourceNames);

        List<List<String>> previewData = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> values = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < sourceNames.size(); columnIndex++) {
                values.add(StringUtils.defaultString(rows.get(rowIndex).get(columnIndex)));
            }
            previewData.add(values);
        }

        TableMetadataRequest targetRequest = TrustedMetadataRequestResolver.table(dataSourceId, databaseName, schemaName,
                tableName);
        List<ImportTargetColumn> targetColumns = targetColumns(targetRequest);
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
                .targetTableName(targetRequest.getTableName())
                .targetColumns(targetColumns)
                .suggestedMapping(suggested)
                .previewLimit(PREVIEW_ROW_LIMIT)
                .build();
    }

    private static List<ImportTargetColumn> targetColumns(TableMetadataRequest target) {
        Connection connection = Chat2DBContext.getConnection();
        return Chat2DBContext.getDbMetaData().columns(connection,
                        target).stream()
                .map(column -> ImportTargetColumn.builder()
                        .name(column.getName())
                        .dataType(column.getColumnType())
                        .nullable(column.getNullable() != null && column.getNullable() == 1)
                        .autoIncrement(Boolean.TRUE.equals(column.getAutoIncrement()))
                        .defaultValue(column.getDefaultValue())
                        .comment(column.getComment())
                        .build())
                .toList();
    }

    private static void requireUniqueSourceColumns(List<String> sourceColumns) {
        HashSet<String> names = new HashSet<>();
        for (String sourceColumn : sourceColumns) {
            if (!names.add(sourceColumn.toUpperCase(Locale.ROOT))) {
                throw new BusinessException("import.preview.duplicateSourceColumns", new Object[]{sourceColumn});
            }
        }
    }

    /**
     * Parses the file with EasyExcel (same code path for preview and execution). The first
     * row is treated as the header; without a header the columns are named column_1..N.
     */
    private static List<Map<Integer, String>> parseRows(File file, int limit) {
        try {
            ImportPreviewListener listener = new ImportPreviewListener(limit);
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
}
