package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.db.ImportTargetColumn;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
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
        return preview(dataSourceId, databaseName, schemaName, tableName, file, null);
    }

    @Override
    public ImportPreview preview(Long dataSourceId, String databaseName, String schemaName,
                                 String tableName, File file, CsvOptions csvOptions) {
        ParsedRows parsedRows = parseRows(file, PREVIEW_ROW_LIMIT, csvOptions);
        if (parsedRows.header().isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, String> header = parsedRows.header();
        List<String> sourceNames = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = StringUtils.defaultIfBlank(header.get(i), "column_" + (i + 1));
            sourceNames.add(name);
        }
        requireUniqueSourceColumns(sourceNames);

        List<List<String>> previewData = new ArrayList<>();
        for (Map<Integer, String> row : parsedRows.data()) {
            List<String> values = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < sourceNames.size(); columnIndex++) {
                values.add(StringUtils.defaultString(row.get(columnIndex)));
            }
            previewData.add(values);
        }

        TableMetadataRequest targetRequest = TrustedMetadataRequestResolver.table(dataSourceId, databaseName, schemaName,
                tableName);
        List<ImportTargetColumn> targetColumns = targetColumns(targetRequest);
        List<ImportColumnMapping> suggested = new ArrayList<>();
        if (parsedRows.syntheticHeader()) {
            List<ImportTargetColumn> importableTargets = targetColumns.stream()
                    .filter(target -> !target.isAutoIncrement())
                    .toList();
            for (int index = 0; index < Math.min(sourceNames.size(), importableTargets.size()); index++) {
                suggested.add(ImportColumnMapping.builder()
                        .sourceColumn(sourceNames.get(index))
                        .targetColumn(importableTargets.get(index).getName())
                        .build());
            }
        } else {
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
    private static ParsedRows parseRows(File file, int limit, CsvOptions csvOptions) {
        if (file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            CsvOptions options = (csvOptions == null ? CsvOptions.defaults() : csvOptions).validate();
            try {
                int previewEndRow = options.getDataStartRow() + limit - 1;
                if (options.getDataEndRow() != null) {
                    previewEndRow = Math.min(previewEndRow, options.getDataEndRow());
                }
                int parseLimit = Math.max(previewEndRow,
                        Boolean.TRUE.equals(options.getHasHeader()) ? options.getHeaderRow() : 0);
                CsvParser.CsvResult result = new CsvParser(options).parse(file.toPath(), parseLimit);
                List<Map<Integer, String>> rows = result.rows();
                if (rows.isEmpty()) {
                    return new ParsedRows(Map.of(), List.of(), false);
                }
                int firstDataIndex = options.getDataStartRow() - 1;
                int dataEndIndex = Math.min(rows.size(), previewEndRow);
                List<Map<Integer, String>> data = firstDataIndex >= dataEndIndex
                        ? List.of() : rows.subList(firstDataIndex, dataEndIndex);
                if (Boolean.TRUE.equals(options.getHasHeader())) {
                    int headerIndex = options.getHeaderRow() - 1;
                    if (headerIndex >= rows.size()) {
                        return new ParsedRows(Map.of(), List.of(), false);
                    }
                    return new ParsedRows(rows.get(headerIndex), data, false);
                }
                int columnCount = data.stream().mapToInt(Map::size).max().orElse(0);
                Map<Integer, String> header = new java.util.LinkedHashMap<>();
                for (int index = 0; index < columnCount; index++) {
                    header.put(index, "column_" + (index + 1));
                }
                return new ParsedRows(header, data, true);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.warn("CSV import preview parse failed for {}", file, e);
                throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
            }
        }
        try {
            ImportPreviewListener listener = new ImportPreviewListener(limit);
            EasyExcel.read(file, listener).excelType(excelType(file)).sheet().headRowNumber(1).doRead();
            List<Map<Integer, String>> rows = listener.rows();
            return rows.isEmpty() ? new ParsedRows(Map.of(), List.of(), false)
                    : new ParsedRows(rows.get(0), rows.subList(1, rows.size()), false);
        } catch (Exception e) {
            log.warn("import preview parse failed for {}", file, e);
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
    }

    private record ParsedRows(Map<Integer, String> header, List<Map<Integer, String>> data,
            boolean syntheticHeader) {
    }

    private static ExcelTypeEnum excelType(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return ExcelTypeEnum.CSV;
        }
        return name.endsWith(".xls") ? ExcelTypeEnum.XLS : ExcelTypeEnum.XLSX;
    }
}
