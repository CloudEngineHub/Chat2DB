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
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    margin-bottom: 12px;
  `,
  encodingInput: css`
    width: 160px;
  `,
  characterInput: css`
    width: 84px;
  `,
  sectionTitle: css`
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
