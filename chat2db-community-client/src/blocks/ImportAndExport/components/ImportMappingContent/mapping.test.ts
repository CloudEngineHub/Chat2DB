import assert from 'node:assert/strict';
import { SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';
import { buildImportMappingRows, buildInitialImportMapping, getDuplicateImportMappings } from './mapping';

const mapping = buildInitialImportMapping(
  ['name', 'email', 'extra_column'],
  [
    { sourceColumn: 'name', targetColumn: 'name' },
    { sourceColumn: 'email', targetColumn: 'email' },
  ],
);

assert.deepEqual(mapping, {
  name: 'name',
  email: 'email',
  extra_column: SKIP_IMPORT_SOURCE_FIELD,
});

assert.deepEqual(
  buildImportMappingRows(['name', 'extra_column'], [{ name: 'id' }, { name: 'name' }, { name: 'note' }], {
    name: 'name',
    extra_column: SKIP_IMPORT_SOURCE_FIELD,
  }),
  [
    { key: 'source:name', kind: 'source', sourceColumn: 'name' },
    { key: 'source:extra_column', kind: 'source', sourceColumn: 'extra_column' },
    { key: 'target:id', kind: 'target', targetColumn: { name: 'id' } },
    { key: 'target:note', kind: 'target', targetColumn: { name: 'note' } },
  ],
);

assert.deepEqual(getDuplicateImportMappings({ ...mapping, extra_column: 'email' }), {
  extra_column: {
    sourceColumn: 'extra_column',
    targetColumn: 'email',
    mappedSource: 'email',
  },
});
assert.deepEqual(getDuplicateImportMappings(mapping), {});
