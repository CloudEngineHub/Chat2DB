import { SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';

interface ISuggestedMapping {
  sourceColumn: string;
  targetColumn: string;
}

export type ImportMappingRow<TTargetColumn extends { name: string }> =
  | {
      key: string;
      kind: 'source';
      sourceColumn: string;
    }
  | {
      key: string;
      kind: 'target';
      targetColumn: TTargetColumn;
    };

export const buildInitialImportMapping = (
  sourceColumns: string[],
  suggestedMapping: ISuggestedMapping[],
): Record<string, string> => {
  const mapping = Object.fromEntries(sourceColumns.map((name) => [name, SKIP_IMPORT_SOURCE_FIELD]));
  suggestedMapping.forEach(({ sourceColumn, targetColumn }) => {
    mapping[sourceColumn] = targetColumn;
  });
  return mapping;
};

export const buildImportMappingRows = <TTargetColumn extends { name: string }>(
  sourceColumns: string[],
  targetColumns: TTargetColumn[],
  mapping: Record<string, string>,
): ImportMappingRow<TTargetColumn>[] => {
  const mappedTargetColumns = new Set(Object.values(mapping));
  return [
    ...sourceColumns.map((name) => ({
      key: `source:${name}`,
      kind: 'source' as const,
      sourceColumn: name,
    })),
    ...targetColumns
      .filter(({ name }) => !mappedTargetColumns.has(name))
      .map((targetColumn) => ({
        key: `target:${targetColumn.name}`,
        kind: 'target' as const,
        targetColumn,
      })),
  ];
};
