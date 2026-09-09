import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Checkbox, Input, Modal, Select, Table, Tooltip } from 'antd';
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

const CUSTOM_CHARACTER = '__custom__';

interface CharacterOptionProps {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  fieldClassName: string;
  controlClassName: string;
  onChange: (value: string) => void;
}

const CharacterOption = ({
  label,
  value,
  options,
  fieldClassName,
  controlClassName,
  onChange,
}: CharacterOptionProps) => {
  const preset = options.some((option) => option.value === value);
  const [custom, setCustom] = useState(!preset);
  const [customValue, setCustomValue] = useState(preset ? '' : value);

  return (
    <label className={fieldClassName}>
      <span>{label}</span>
      <div className={controlClassName} style={custom ? { gridTemplateColumns: 'minmax(0, 1fr) 56px' } : undefined}>
        <Select
          value={custom ? CUSTOM_CHARACTER : value}
          options={[...options, { value: CUSTOM_CHARACTER, label: i18n('workspace.importExport.customCharacter') }]}
          onChange={(nextValue) => {
            if (nextValue === CUSTOM_CHARACTER) {
              setCustom(true);
              return;
            }
            setCustom(false);
            onChange(nextValue);
          }}
        />
        {custom && (
          <Input
            aria-label={label}
            maxLength={1}
            value={customValue}
            onChange={(event) => {
              const nextValue = event.target.value;
              setCustomValue(nextValue);
              if (nextValue) {
                onChange(nextValue);
              }
            }}
          />
        )}
      </div>
    </label>
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
        setMapping(buildInitialImportMapping(data.sourceColumns, data.suggestedMapping));
      })
      .catch((e) => active && setError(resolveErrorMessage(e)))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [csvOptions, dataSourceId, databaseName, fileId, isCsv, resolveErrorMessage, schemaName, tableName]);

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
      {error && <div className={styles.error}>{error}</div>}
      {isCsv && (
        <div className={styles.csvOptions}>
          <strong className={styles.sectionTitle}>{i18n('workspace.importExport.csvOptions')}</strong>
          <label className={styles.csvOptionField}>
            <span>{i18n('workspace.importExport.encoding')}</span>
            <div className={styles.encodingControl}>
              <LocalFileEncodingSelect
                charset={csvOptions.encoding === 'AUTO' ? undefined : csvOptions.encoding}
                disabled={executing}
                onEncodingChange={async (encoding) => {
                  setCsvOptions((current) => ({ ...current, encoding: encoding || 'AUTO' }));
                }}
              />
            </div>
          </label>
          <CharacterOption
            label={i18n('workspace.importExport.delimiter')}
            value={csvOptions.delimiter}
            fieldClassName={styles.csvOptionField}
            controlClassName={styles.csvOptionControl}
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
            controlClassName={styles.csvOptionControl}
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
            controlClassName={styles.csvOptionControl}
            options={[
              { value: csvOptions.quote, label: i18n('workspace.importExport.escapeRepeatedQualifier') },
              { value: '\\', label: i18n('workspace.importExport.escapeBackslash') },
            ]}
            onChange={(escape) => setCsvOptions((current) => ({ ...current, escape }))}
          />
          <div className={styles.csvBooleanOptions}>
            <Checkbox
              checked={csvOptions.hasHeader}
              onChange={(event) => setCsvOptions((current) => ({ ...current, hasHeader: event.target.checked }))}
            >
              {i18n('workspace.importExport.hasHeader')}
            </Checkbox>
            <Checkbox
              checked={csvOptions.emptyAsNull}
              onChange={(event) => setCsvOptions((current) => ({ ...current, emptyAsNull: event.target.checked }))}
            >
              {i18n('workspace.importExport.emptyAsNull')}
            </Checkbox>
          </div>
        </div>
      )}
      {preview && (
        <>
          <div className={styles.toolbar}>
            <strong className={styles.mappingSectionTitle}>{i18n('workspace.importExport.fieldMapping')}</strong>
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
            <Button type="primary" loading={executing} onClick={execute}>
              {i18n('common.button.execute')}
            </Button>
          </div>
        </>
      )}
    </div>
  );
};

export default ImportMappingContent;
