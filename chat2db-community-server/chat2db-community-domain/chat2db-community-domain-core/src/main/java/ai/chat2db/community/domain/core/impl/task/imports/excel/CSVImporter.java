package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.CsvParser;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import com.alibaba.excel.support.ExcelTypeEnum;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public class CSVImporter extends BaseExcelImporter implements IImportStrategy {

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        CsvOptions options = (spec.getCsvOptions() == null ? CsvOptions.defaults() : spec.getCsvOptions()).validate();
        NoModelDataListener listener = new NoModelDataListener(spec, context, columns);
        boolean[] initialized = {false};
        new CsvParser(options).forEachRow(Path.of(spec.getSourceFile()), row -> {
            if (!initialized[0]) {
                initialized[0] = true;
                if (Boolean.TRUE.equals(options.getHasHeader())) {
                    listener.acceptHead(row);
                    return;
                }
                listener.acceptHead(syntheticHeader(Math.max(row.size(), mappedSourceColumnCount(spec))));
            }
            listener.acceptRow(row);
        }, context::checkCancelled);
        if (initialized[0]) {
            listener.finish();
        }
    }

    private static Map<Integer, String> syntheticHeader(int columnCount) {
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int index = 0; index < columnCount; index++) {
            header.put(index, "column_" + (index + 1));
        }
        return header;
    }

    private static int mappedSourceColumnCount(ImportTaskSpec spec) {
        if (spec.getColumnMappings() == null) {
            return 0;
        }
        return spec.getColumnMappings().stream()
                .map(mapping -> mapping.getSourceColumn())
                .filter(source -> source != null && source.startsWith("column_"))
                .mapToInt(source -> {
                    try {
                        return Integer.parseInt(source.substring("column_".length()));
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                })
                .max()
                .orElse(0);
    }

    @Override
    protected ExcelTypeEnum getExcelType() {
        return ExcelTypeEnum.CSV;
    }
}
