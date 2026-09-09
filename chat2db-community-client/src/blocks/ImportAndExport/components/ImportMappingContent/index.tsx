import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Checkbox, Collapse, Divider, Input, InputNumber, Modal, Select, Spin, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { TriangleAlert } from 'lucide-react';
import {
  ImportExportFileType,
  ImportPreviewErrorCode,
  ImportUnmappedTarget,
  SKIP_IMPORT_SOURCE_FIELD,
} from '@/constants/importExport';
import i18n from '@/i18n';
import sqlService, { ICsvOptions, IImportPreview } from '@/service/sql';
import {
  buildImportMappingRows,
  buildInitialImportMapping,
  getDuplicateImportMappings,
  getImportPreviewErrorMessage,
  ImportMappingRow,
} from './mapping';
import { useStyles } from './style';
import type { FileUrl } from '@/components/UploadLocalFile';
import { stageSelectedImportFile } from './fileStaging';
import LocalFileEncodingSelect from '@/components/LocalFileEncodingSelect';
import {
  buildCsvOptionsForTaskSubmit,
  DEFAULT_CSV_OPTIONS,
  getCsvDateTimeExamples,
  inferImportFileFormat,
} from '../../utils/csvOptions';

interface IProps {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  tableName: string;
  file: FileUrl;
  onSubmitted: (taskId: number) => void;
}

interface CharacterOptionProps {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  fieldClassName: string;
  customInputClassName: string;
  allowCustom?: boolean;
  disabled?: boolean;
  onChange: (value: string) => void;
}

const CharacterOption = ({
  label,
  value,
  options,
  fieldClassName,
  customInputClassName,
  allowCustom = true,
  disabled,
  onChange,
}: CharacterOptionProps) => {
  const preset = options.some((option) => option.value === value);
  const [customValue, setCustomValue] = useState(preset ? '' : value);
  useEffect(() => {
    setCustomValue(preset ? '' : value);
  }, [preset, value]);
  const selectOptions =
    preset || !allowCustom
      ? options
      : [...options, { value, label: i18n('workspace.importExport.customCharacterValue', value) }];

  return (
    <div className={fieldClassName}>
      <span>{label}</span>
      <Select
        value={value}
        options={selectOptions}
        disabled={disabled}
        onChange={onChange}
        dropdownRender={
          allowCustom
            ? (menu) => (
                <>
                  {menu}
                  <Divider style={{ margin: '4px 0' }} />
                  <div className={customInputClassName} onMouseDown={(event) => event.stopPropagation()}>
                    <Input
                      aria-label={i18n('workspace.importExport.customCharacter')}
                      maxLength={1}
                      placeholder={i18n('workspace.importExport.customCharacter')}
                      value={customValue}
                      disabled={disabled}
                      onKeyDown={(event) => event.stopPropagation()}
                      onChange={(event) => {
                        const nextValue = event.target.value;
                        setCustomValue(nextValue);
                        if (nextValue) {
                          onChange(nextValue);
                        }
                      }}
                    />
                  </div>
                </>
              )
            : undefined
        }
      />
    </div>
  );
};

/**
 * Database-independent import preview and column mapping. Loads a bounded preview of the
 * file, lets the user remap source fields to target columns (or skip them), chooses how
 * unmapped target columns are filled (DEFAULT or NULL), executes the import, and reports
 * task progress. Preview and execution share the backend parser.
 */
const ImportMappingContent = ({ dataSourceId, databaseName, schemaName, tableName, file, onSubmitted }: IProps) => {
  const { styles, cx } = useStyles();
  const [modal, modalContextHolder] = Modal.useModal();
  const [preview, setPreview] = useState<IImportPreview | null>(null);
  const [fileId, setFileId] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [unmappedTarget, setUnmappedTarget] = useState(ImportUnmappedTarget.DEFAULT);
  const [executing, setExecuting] = useState(false);
  const [csvOptions, setCsvOptions] = useState<ICsvOptions>(DEFAULT_CSV_OPTIONS);
  const selectedFileName = file.fileName || file.file?.name || file.filePath || '';
  const isCsv = inferImportFileFormat(selectedFileName) === ImportExportFileType.CSV;
  const currentPreviewKey = JSON.stringify({
    dataSourceId,
    databaseName,
    schemaName,
    tableName,
    fileId,
    csvOptions: isCsv ? csvOptions : undefined,
  });
  const [loadedPreviewKey, setLoadedPreviewKey] = useState<string>();
  const resolveErrorMessage = useCallback(
    (requestError: unknown) =>
      getImportPreviewErrorMessage(requestError, i18n('common.text.failure'), {
        [ImportPreviewErrorCode.DUPLICATE_SOURCE_COLUMNS]: i18n('workspace.importExport.duplicateSourceColumns'),
      }),
    [],
  );

  useEffect(() => {
    let active = true;
    setFileId(undefined);
    setLoading(true);
    setError(null);
    stageSelectedImportFile(file, sqlService.uploadImportFile, sqlService.stageDesktopImportFile)
      .then((id) => {
        if (active) {
          setFileId(id);
        }
      })
      .catch((e) => {
        if (active) {
          setError(resolveErrorMessage(e));
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [file, resolveErrorMessage]);

  useEffect(() => {
    if (!fileId) {
      return;
    }
    let active = true;
    let validatedCsvOptions: ICsvOptions | undefined;
    try {
      validatedCsvOptions = buildCsvOptionsForTaskSubmit(isCsv, csvOptions);
    } catch (e) {
      setPreview(null);
      setLoadedPreviewKey(undefined);
      setMapping({});
      setError(e instanceof Error ? e.message : i18n('common.text.failure'));
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    sqlService
      .getImportPreview({
        dataSourceId,
        databaseName,
        schemaName,
        tableName,
        fileId,
        csvOptions: validatedCsvOptions,
      })
      .then((data) => {
        if (!active) {
          return;
        }
        setPreview(data);
        setLoadedPreviewKey(currentPreviewKey);
        setMapping(buildInitialImportMapping(data.sourceColumns, data.suggestedMapping));
      })
      .catch((e) => {
        if (active) {
          setPreview(null);
          setLoadedPreviewKey(undefined);
          setMapping({});
          setError(resolveErrorMessage(e));
        }
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [
    currentPreviewKey,
    csvOptions,
    dataSourceId,
    databaseName,
    fileId,
    isCsv,
    resolveErrorMessage,
    schemaName,
    tableName,
  ]);

  const targetOptions = useMemo(() => {
    if (!preview) {
      return [];
    }
    return [
      { value: SKIP_IMPORT_SOURCE_FIELD, label: i18n('workspace.importExport.skipSourceField') },
      ...preview.targetColumns.map((c) => ({
        value: c.name,
        label: `${c.name} (${c.dataType}${c.nullable ? '' : ', NOT NULL'})${c.comment ? ` - ${c.comment}` : ''}`,
      })),
    ];
  }, [preview]);

  const blockedColumns = useMemo(() => {
    if (!preview) {
      return [];
    }
    return preview.targetColumns.filter(
      (c) =>
        !c.nullable &&
        !c.autoIncrement &&
        !Object.values(mapping).includes(c.name) &&
        (unmappedTarget === ImportUnmappedTarget.NULL ||
          (c.defaultValue === null && unmappedTarget === ImportUnmappedTarget.DEFAULT)),
    );
  }, [preview, mapping, unmappedTarget]);

  const mappingRows = preview ? buildImportMappingRows(preview.sourceColumns, preview.targetColumns, mapping) : [];
  const duplicateMappings = getDuplicateImportMappings(mapping);
  const updateMapping = (sourceColumn: string, targetColumn: string) => {
    setMapping((previous) => ({ ...previous, [sourceColumn]: targetColumn }));
  };

  const columns: ColumnsType<ImportMappingRow<IImportPreview['targetColumns'][number]>> = [
    {
      title: i18n('workspace.importExport.sourceField'),
      width: '28%',
      render: (_, record) =>
        record.kind === 'source' ? (
          record.sourceColumn
        ) : (
          <span className={styles.unmappedSource}>{i18n('workspace.importExport.unmapped')}</span>
        ),
    },
    {
      title: i18n('workspace.importExport.targetColumn'),
      width: '52%',
      render: (_, record) =>
        record.kind === 'source' ? (
          <div className={styles.targetColumnCell}>
            {duplicateMappings[record.sourceColumn] && (
              <span className={styles.mappingWarningSlot}>
                <Tooltip
                  title={i18n(
                    'workspace.importExport.duplicateMappingContent',
                    duplicateMappings[record.sourceColumn].targetColumn,
                    duplicateMappings[record.sourceColumn].mappedSource,
                  )}
                >
                  <span
                    className={styles.mappingWarningIcon}
                    role="img"
                    aria-label={i18n(
                      'workspace.importExport.duplicateMappingContent',
                      duplicateMappings[record.sourceColumn].targetColumn,
                      duplicateMappings[record.sourceColumn].mappedSource,
                    )}
                  >
                    <TriangleAlert size={16} />
                  </span>
                </Tooltip>
              </span>
            )}
            <Select
              className={cx(
                styles.targetColumnSelect,
                duplicateMappings[record.sourceColumn] && styles.targetColumnSelectWarning,
              )}
              value={mapping[record.sourceColumn]}
              options={targetOptions}
              onChange={(value) => updateMapping(record.sourceColumn, value)}
            />
          </div>
        ) : (
          targetOptions.find(({ value }) => value === record.targetColumn.name)?.label
        ),
    },
    {
      title: i18n('workspace.importExport.mappingStatus'),
      width: '20%',
      render: (_, record) => {
        if (record.kind === 'source') {
          return mapping[record.sourceColumn] === SKIP_IMPORT_SOURCE_FIELD
            ? i18n('workspace.importExport.skipped')
            : i18n('workspace.importExport.mapped');
        }
        if (blockedColumns.some(({ name }) => name === record.targetColumn.name)) {
          return <span className={styles.requiredStatus}>{i18n('workspace.importExport.unmappedRequired')}</span>;
        }
        if (record.targetColumn.autoIncrement) {
          return i18n('workspace.importExport.unmappedAutoIncrement');
        }
        return unmappedTarget === ImportUnmappedTarget.NULL
          ? i18n('workspace.importExport.unmappedNullValue')
          : i18n('workspace.importExport.unmappedDefaultValue');
      },
    },
  ];

  const previewColumns: ColumnsType<{ key: number; values: string[] }> =
    preview?.sourceColumns.map((column, index) => ({
      title: column,
      width: 180,
      ellipsis: true,
      render: (_, record) => record.values[index],
    })) || [];

  const previewData =
    preview?.previewData.map((values, index) => ({
      key: index,
      values,
    })) || [];

  const execute = () => {
    const duplicateMapping = Object.values(duplicateMappings)[0];
    if (duplicateMapping) {
      modal.error({
        title: i18n('workspace.importExport.duplicateMappingTitle'),
        content: i18n(
          'workspace.importExport.duplicateMappingContent',
          duplicateMapping.targetColumn,
          duplicateMapping.mappedSource,
        ),
      });
      return;
    }
    if (blockedColumns.length > 0) {
      modal.error({
        title: i18n('workspace.importExport.requiredUnmapped'),
        content: blockedColumns.map((c) => `${c.name} (${c.dataType})`).join(', '),
      });
      return;
    }
    setExecuting(true);
    setError(null);
    if (!fileId) {
      setExecuting(false);
      return;
    }
    let taskCsvOptions: ICsvOptions | undefined;
    try {
      taskCsvOptions = buildCsvOptionsForTaskSubmit(isCsv, csvOptions);
    } catch (e) {
      setExecuting(false);
      setError(e instanceof Error ? e.message : i18n('common.text.failure'));
      return;
    }
    sqlService
      .executeImportWithMapping({
        dataSourceId,
        databaseName,
        schemaName,
        tableName,
        fileId,
        mappings: Object.entries(mapping)
          .filter(([, target]) => target && target !== SKIP_IMPORT_SOURCE_FIELD)
          .map(([source, target]) => ({ sourceColumn: source, targetColumn: target })),
        unmappedTarget,
        csvOptions: taskCsvOptions,
      })
      .then((result) => onSubmitted(result.taskId))
      .catch((e) => setError(resolveErrorMessage(e)))
      .finally(() => setExecuting(false));
  };

  return (
    <div className={styles.container}>
      {modalContextHolder}
      {error && preview && <div className={styles.error}>{error}</div>}
      {isCsv && (
        <div className={styles.csvOptions}>
          <div className={styles.advancedOptions}>
            <Collapse
              ghost
              size="small"
              items={[
                {
                  key: 'csvFormat',
                  label: i18n('workspace.importExport.csvFormat'),
                  children: (
                    <div className={styles.csvFormatOptions}>
                      <div className={styles.csvOptionField}>
                        <span>{i18n('workspace.importExport.encoding')}</span>
                        <LocalFileEncodingSelect
                          className={styles.fullWidthControl}
                          charset={csvOptions.encoding === 'AUTO' ? undefined : csvOptions.encoding}
                          disabled={executing}
                          size="middle"
                          variant="outlined"
                          onEncodingChange={async (encoding) => {
                            setCsvOptions((current) => ({ ...current, encoding: encoding || 'AUTO' }));
                          }}
                        />
                      </div>
                      <CharacterOption
                        label={i18n('workspace.importExport.delimiter')}
                        value={csvOptions.delimiter}
                        fieldClassName={styles.csvOptionField}
                        customInputClassName={styles.customCharacterInput}
                        options={[
                          { value: ',', label: i18n('workspace.importExport.delimiterComma') },
                          { value: ';', label: i18n('workspace.importExport.delimiterSemicolon') },
                          { value: '\t', label: i18n('workspace.importExport.delimiterTab') },
                          { value: '|', label: i18n('workspace.importExport.delimiterPipe') },
                        ]}
                        onChange={(delimiter) => setCsvOptions((current) => ({ ...current, delimiter }))}
                      />
                      <CharacterOption
                        label={i18n('workspace.importExport.textQualifier')}
                        value={csvOptions.quote}
                        fieldClassName={styles.csvOptionField}
                        customInputClassName={styles.customCharacterInput}
                        options={[
                          { value: '"', label: i18n('workspace.importExport.quoteDouble') },
                          { value: "'", label: i18n('workspace.importExport.quoteSingle') },
                          { value: '`', label: i18n('workspace.importExport.quoteBacktick') },
                          { value: '~', label: i18n('workspace.importExport.quoteTilde') },
                        ]}
                        onChange={(quote) =>
                          setCsvOptions((current) => ({
                            ...current,
                            quote,
                            escape: current.escape === current.quote ? quote : current.escape,
                          }))
                        }
                      />
                      <CharacterOption
                        label={i18n('workspace.importExport.escapeMethod')}
                        value={csvOptions.escape}
                        fieldClassName={styles.csvOptionField}
                        customInputClassName={styles.customCharacterInput}
                        options={[
                          { value: csvOptions.quote, label: i18n('workspace.importExport.escapeRepeatedQualifier') },
                          { value: '\\', label: i18n('workspace.importExport.escapeBackslash') },
                        ]}
                        onChange={(escape) => setCsvOptions((current) => ({ ...current, escape }))}
                      />
                    </div>
                  ),
                },
                {
                  key: 'sourceRows',
                  label: i18n('workspace.importExport.sourceRows'),
                  children: (
                    <div className={styles.sourceRowOptions}>
                      <Checkbox
                        className={styles.sourceRowHasHeader}
                        checked={csvOptions.hasHeader}
                        onChange={(event) =>
                          setCsvOptions((current) => ({
                            ...current,
                            hasHeader: event.target.checked,
                            dataStartRow: event.target.checked
                              ? Math.max(current.dataStartRow, current.headerRow + 1)
                              : 1,
                            dataEndRow:
                              event.target.checked && current.dataEndRow && current.dataEndRow < current.headerRow + 1
                                ? current.headerRow + 1
                                : current.dataEndRow,
                          }))
                        }
                      >
                        {i18n('workspace.importExport.hasHeader')}
                      </Checkbox>
                      <div className={styles.csvOptionField}>
                        <span>{i18n('workspace.importExport.headerRow')}</span>
                        <InputNumber
                          min={1}
                          precision={0}
                          disabled={!csvOptions.hasHeader}
                          value={csvOptions.headerRow}
                          onChange={(value) => {
                            if (value !== null) {
                              setCsvOptions((current) => ({
                                ...current,
                                headerRow: value,
                                dataStartRow: Math.max(current.dataStartRow, value + 1),
                                dataEndRow:
                                  current.dataEndRow && current.dataEndRow < value + 1 ? value + 1 : current.dataEndRow,
                              }));
                            }
                          }}
                        />
                      </div>
                      <label className={styles.csvOptionField}>
                        <span>{i18n('workspace.importExport.dataStartRow')}</span>
                        <InputNumber
                          min={csvOptions.hasHeader ? csvOptions.headerRow + 1 : 1}
                          precision={0}
                          value={csvOptions.dataStartRow}
                          onChange={(value) =>
                            value &&
                            setCsvOptions((current) => ({
                              ...current,
                              dataStartRow: value,
                              dataEndRow: current.dataEndRow && current.dataEndRow < value ? value : current.dataEndRow,
                            }))
                          }
                        />
                      </label>
                      <label className={styles.csvOptionField}>
                        <span>{i18n('workspace.importExport.dataEndRow')}</span>
                        <InputNumber
                          min={csvOptions.dataStartRow}
                          precision={0}
                          placeholder={i18n('workspace.importExport.endOfFile')}
                          value={csvOptions.dataEndRow}
                          onChange={(value) =>
                            setCsvOptions((current) => ({
                              ...current,
                              dataEndRow: value === null ? undefined : value,
                            }))
                          }
                        />
                      </label>
                    </div>
                  ),
                },
                {
                  key: 'formats',
                  label: i18n('workspace.importExport.dateTimeFormats'),
                  children: (
                    <div className={styles.formatOptions}>
                      <label className={styles.csvOptionField}>
                        <span>{i18n('workspace.importExport.dateOrder')}</span>
                        <Select
                          value={csvOptions.dateOrder}
                          options={['MDY', 'DMY', 'YMD', 'YDM', 'DYM', 'MYD'].map((value) => ({ value, label: value }))}
                          onChange={(dateOrder) => setCsvOptions((current) => ({ ...current, dateOrder }))}
                        />
                      </label>
                      <label className={styles.csvOptionField}>
                        <span>{i18n('workspace.importExport.dateTimeOrder')}</span>
                        <Select
                          value={csvOptions.dateTimeOrder}
                          options={[
                            { value: 'DATE_TIME', label: i18n('workspace.importExport.dateFirst') },
                            { value: 'TIME_DATE', label: i18n('workspace.importExport.timeFirst') },
                            { value: 'DATE_TIME_TIMEZONE', label: i18n('workspace.importExport.dateTimeTimezone') },
                            { value: 'TIME_DATE_TIMEZONE', label: i18n('workspace.importExport.timeDateTimezone') },
                            { value: 'TIME_TIMEZONE_DATE', label: i18n('workspace.importExport.timeTimezoneDate') },
                          ]}
                          onChange={(dateTimeOrder) => setCsvOptions((current) => ({ ...current, dateTimeOrder }))}
                        />
                      </label>
                      <CharacterOption
                        label={i18n('workspace.importExport.dateDelimiter')}
                        value={csvOptions.dateDelimiter}
                        fieldClassName={styles.csvOptionField}
                        customInputClassName={styles.customCharacterInput}
                        options={[
                          { value: '-', label: i18n('workspace.importExport.delimiterDash') },
                          { value: '/', label: i18n('workspace.importExport.delimiterSlash') },
                          { value: '.', label: i18n('workspace.importExport.delimiterDot') },
                        ]}
                        onChange={(dateDelimiter) =>
                          setCsvOptions((current) => ({
                            ...current,
                            dateDelimiter,
                          }))
                        }
                      />
                      <CharacterOption
                        label={i18n('workspace.importExport.yearDelimiter')}
                        value={csvOptions.yearDelimiter}
                        fieldClassName={styles.csvOptionField}
                        customInputClassName={styles.customCharacterInput}
                        options={[
                          { value: '-', label: i18n('workspace.importExport.delimiterDash') },
                          { value: '/', label: i18n('workspace.importExport.delimiterSlash') },
                          { value: '.', label: i18n('workspace.importExport.delimiterDot') },
                        ]}
                        onChange={(yearDelimiter) => setCsvOptions((current) => ({ ...current, yearDelimiter }))}
                      />
                      <CharacterOption
                        label={i18n('workspace.importExport.timeDelimiter')}
                        value={csvOptions.timeDelimiter}
                        fieldClassName={styles.csvOptionField}
                        customInputClassName={styles.customCharacterInput}
                        options={[
                          { value: ':', label: i18n('workspace.importExport.delimiterColon') },
                          { value: '.', label: i18n('workspace.importExport.delimiterDot') },
                        ]}
                        onChange={(timeDelimiter) => setCsvOptions((current) => ({ ...current, timeDelimiter }))}
                      />
                      <CharacterOption
                        label={i18n('workspace.importExport.decimalSymbol')}
                        value={csvOptions.decimalSymbol}
                        fieldClassName={styles.csvOptionField}
                        customInputClassName={styles.customCharacterInput}
                        allowCustom={false}
                        options={[
                          { value: '.', label: i18n('workspace.importExport.delimiterDot') },
                          { value: ',', label: i18n('workspace.importExport.delimiterComma') },
                        ]}
                        onChange={(decimalSymbol) => setCsvOptions((current) => ({ ...current, decimalSymbol }))}
                      />
                      <div className={styles.dateExamples}>
                        <span>{i18n('workspace.importExport.dateTimeExample')}</span>
                        {getCsvDateTimeExamples(csvOptions).map((example) => (
                          <code key={example}>{example}</code>
                        ))}
                      </div>
                    </div>
                  ),
                },
              ]}
            />
          </div>
        </div>
      )}
      {!preview && (
        <div className={styles.previewState} role={error ? undefined : 'status'}>
          {error ? (
            <div className={styles.previewError} role="alert">
              <TriangleAlert size={24} />
              <span>{error}</span>
            </div>
          ) : (
            <Spin />
          )}
        </div>
      )}
      {preview && (
        <>
          <div className={styles.toolbar}>
            <strong className={styles.mappingSectionTitle}>{i18n('workspace.importExport.fieldMapping')}</strong>
            {isCsv && (
              <Checkbox
                checked={csvOptions.emptyAsNull}
                onChange={(event) => setCsvOptions((current) => ({ ...current, emptyAsNull: event.target.checked }))}
              >
                {i18n('workspace.importExport.emptyAsNull')}
              </Checkbox>
            )}
            <Select
              className={styles.unmappedTargetSelect}
              value={unmappedTarget}
              onChange={(v) => setUnmappedTarget(v)}
              options={[
                { value: ImportUnmappedTarget.DEFAULT, label: i18n('workspace.importExport.unmappedDefault') },
                { value: ImportUnmappedTarget.NULL, label: i18n('workspace.importExport.unmappedNull') },
              ]}
            />
          </div>
          <Table
            className={styles.mappingTable}
            size="small"
            rowKey="key"
            columns={columns}
            dataSource={mappingRows}
            loading={loading}
            pagination={false}
            tableLayout="fixed"
            scroll={{ y: 220 }}
          />
          <strong className={styles.previewTitle}>
            {i18n('workspace.importExport.dataPreview', preview.previewLimit)}
          </strong>
          <Table
            className={styles.previewTable}
            size="small"
            rowKey="key"
            columns={previewColumns}
            dataSource={previewData}
            pagination={false}
            scroll={{ x: 'max-content', y: 380 }}
          />
          <div className={styles.actions}>
            <Button
              type="primary"
              loading={executing}
              disabled={loading || !fileId || loadedPreviewKey !== currentPreviewKey}
              onClick={execute}
            >
              {i18n('common.button.execute')}
            </Button>
          </div>
        </>
      )}
    </div>
  );
};

export default ImportMappingContent;
