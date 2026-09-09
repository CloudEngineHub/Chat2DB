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
import java.util.List;
import java.util.Map;

public class CSVImporter extends BaseExcelImporter implements IImportStrategy {

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        CsvOptions options = (spec.getCsvOptions() == null ? CsvOptions.defaults() : spec.getCsvOptions()).validate();
        CsvParser.CsvResult result;
        result = new CsvParser(options).parse(Path.of(spec.getSourceFile()), Integer.MAX_VALUE);

        List<Map<Integer, String>> rows = result.rows();
        if (rows.isEmpty()) {
            return;
        }
        NoModelDataListener listener = new NoModelDataListener(spec, context, columns);
        int firstDataRow;
        if (Boolean.TRUE.equals(options.getHasHeader())) {
            listener.acceptHead(rows.get(0));
            firstDataRow = 1;
        } else {
            int columnCount = rows.stream().mapToInt(Map::size).max().orElse(0);
            Map<Integer, String> header = new LinkedHashMap<>();
            for (int index = 0; index < columnCount; index++) {
                header.put(index, "column_" + (index + 1));
            }
            listener.acceptHead(header);
            firstDataRow = 0;
        }
        for (int index = firstDataRow; index < rows.size(); index++) {
            listener.acceptRow(rows.get(index));
        }
        listener.finish();
    }

    @Override
    protected ExcelTypeEnum getExcelType() {
        return ExcelTypeEnum.CSV;
    }
}
