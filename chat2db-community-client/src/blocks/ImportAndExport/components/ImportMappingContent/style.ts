import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  container: css`
    min-width: 0;
  `,
  error: css`
    margin-bottom: 8px;
    color: ${token.colorError};
  `,
  toolbar: css`
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    margin-bottom: 8px;
  `,
  csvOptions: css`
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 8px;
    margin-bottom: 12px;
  `,
  csvOptionField: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;

    > span:first-child {
      color: ${token.colorTextSecondary};
      font-size: 12px;
    }
  `,
  csvOptionControl: css`
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 6px;

    .ant-select,
    .ant-input {
      width: 100%;
    }
  `,
  encodingControl: css`
    display: flex;
    min-height: 32px;
    align-items: center;
    border: 1px solid ${token.colorBorder};
    border-radius: ${token.borderRadius}px;
    padding: 0 8px;
  `,
  csvBooleanOptions: css`
    display: flex;
    grid-column: 1 / -1;
    gap: 20px;
    align-items: center;
  `,
  sectionTitle: css`
    grid-column: 1 / -1;
    font-size: 14px;
  `,
  mappingSectionTitle: css`
    margin-right: auto;
    font-size: 14px;
  `,
  unmappedTargetSelect: css`
    width: 220px;
  `,
  unmappedSource: css`
    color: ${token.colorTextSecondary};
  `,
  requiredStatus: css`
    color: ${token.colorError};
  `,
  targetColumnCell: css`
    position: relative;
  `,
  mappingWarningSlot: css`
    position: absolute;
    z-index: 1;
    top: 50%;
    left: 10px;
    display: flex;
    width: 16px;
    height: 16px;
    transform: translateY(-50%);
  `,
  mappingWarningIcon: css`
    display: flex;
    color: ${token.colorWarning};
    cursor: help;
  `,
  targetColumnSelect: css`
    width: 100%;
  `,
  targetColumnSelectWarning: css`
    .ant-select-selector {
      padding-left: 34px !important;
    }
  `,
  mappingTable: css`
    .ant-table-body {
      overflow-y: auto !important;
    }

    .ant-table-tbody > tr > td {
      border-bottom: 0;
    }
  `,
  previewTitle: css`
    display: block;
    margin: 16px 0 8px;
    font-size: 14px;
  `,
  previewTable: css`
    .ant-table-body {
      overflow-y: auto !important;
    }
  `,
  actions: css`
    margin-top: 12px;
    text-align: right;
  `,
}));
