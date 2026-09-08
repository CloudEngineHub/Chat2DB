import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Modal, Select, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { TriangleAlert } from 'lucide-react';
import { ImportPreviewErrorCode, ImportUnmappedTarget, SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';
import i18n from '@/i18n';
import sqlService, { IImportPreview } from '@/service/sql';
import {
  buildImportMappingRows,
  buildInitialImportMapping,
  getDuplicateImportMappings,
  getImportPreviewErrorMessage,
  ImportMappingRow,
} from './mapping';
import { useStyles } from './style';

interface IProps {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  tableName: string;
  file: File;
  onSubmitted: (taskId: number) => void;
}

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
  const resolveErrorMessage = useCallback(
    (requestError: unknown) =>
      getImportPreviewErrorMessage(requestError, i18n('common.text.failure'), {
        [ImportPreviewErrorCode.DUPLICATE_SOURCE_COLUMNS]: i18n('workspace.importExport.duplicateSourceColumns'),
      }),
    [],
  );

  const load = useCallback(
    (stagedFileId: string) => {
      setLoading(true);
      setError(null);
      sqlService
        .getImportPreview({ dataSourceId, databaseName, schemaName, tableName, fileId: stagedFileId })
        .then((data) => {
          setPreview(data);
          setMapping(buildInitialImportMapping(data.sourceColumns, data.suggestedMapping));
        })
        .catch((e) => {
          setError(resolveErrorMessage(e));
        })
        .finally(() => setLoading(false));
    },
    [dataSourceId, databaseName, schemaName, tableName, resolveErrorMessage],
  );

  useEffect(() => {
    setLoading(true);
    setError(null);
    sqlService
      .uploadImportFile({ file })
      .then((id) => {
        setFileId(id);
        load(id);
      })
      .catch((e) => {
        setError(resolveErrorMessage(e));
        setLoading(false);
      });
  }, [file, load, resolveErrorMessage]);

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
      })
      .then((result) => onSubmitted(result.taskId))
      .catch((e) => setError(resolveErrorMessage(e)))
      .finally(() => setExecuting(false));
  };

  return (
    <div className={styles.container}>
      {modalContextHolder}
      {error && <div className={styles.error}>{error}</div>}
      {preview && (
        <>
          <div className={styles.toolbar}>
            <strong className={styles.sectionTitle}>{i18n('workspace.importExport.fieldMapping')}</strong>
            <Select
              className={styles.unmappedTargetSelect}
              value={unmappedTarget}
              onChange={(v) => setUnmappedTarget(v)}
              options={[
                { value: ImportUnmappedTarget.DEFAULT, label: i18n('workspace.importExport.unmappedDefault') },
                { value: ImportUnmappedTarget.NULL, label: i18n('workspace.importExport.unmappedNull') },
              ]}
            />
            <Button size="small" onClick={() => fileId && load(fileId)} loading={loading}>
              {i18n('common.button.refresh')}
            </Button>
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
