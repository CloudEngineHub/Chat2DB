import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  container: css`
    min-width: 0;
  `,
  error: css`
    margin-bottom: 8px;
    color: ${token.colorError};
  `,
  previewState: css`
    display: flex;
    min-height: 430px;
    align-items: center;
    justify-content: center;
  `,
  previewError: css`
    display: flex;
    max-width: calc(100% - 32px);
    flex-direction: column;
    align-items: center;
    gap: 10px;
    color: ${token.colorError};
    line-height: 1.6;
    text-align: center;
    white-space: nowrap;

    @media (max-width: 720px) {
      white-space: normal;
    }
  `,
  toolbar: css`
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    margin-bottom: 8px;
  `,
  csvOptions: css`
    margin-bottom: 12px;
  `,
  csvOptionField: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;

    > span:first-child {
      display: flex;
      align-items: center;
      gap: 6px;
      color: ${token.colorTextSecondary};
      font-size: 12px;
    }
  `,
  fullWidthControl: css`
    width: 100%;
  `,
  customCharacterInput: css`
    padding: 4px 8px;
  `,
  advancedOptions: css`
    padding-top: 4px;

    .ant-collapse-header {
      padding: 6px 0 !important;
      font-weight: 600;
    }

    .ant-collapse-content-box {
      padding: 4px 0 10px !important;
    }
  `,
  csvFormatOptions: css`
    display: grid;
    grid-template-columns: 130px 150px 160px 170px;
    gap: 8px;

    @media (max-width: 900px) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  `,
  sourceRowOptions: css`
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 160px));
    gap: 8px;

    .ant-input-number {
      width: 100%;
    }
  `,
  sourceRowHasHeader: css`
    grid-column: 1 / -1;
    width: fit-content;
    font-size: 12px;
  `,
  formatOptions: css`
    display: grid;
    grid-template-columns: repeat(5, minmax(120px, 1fr));
    gap: 8px;

    @media (max-width: 900px) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  `,
  dateDelimiterGroup: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 8px;
  `,
  yearDelimiterOption: css`
    display: flex;
    flex-direction: column;
    gap: 8px;

    .ant-checkbox-wrapper {
      color: ${token.colorTextSecondary};
      font-size: 12px;
      white-space: nowrap;
    }
  `,
  dateExamples: css`
    display: grid;
    grid-column: 1 / -1;
    grid-template-columns: auto repeat(4, minmax(0, 1fr));
    gap: 8px;
    align-items: center;
    color: ${token.colorTextSecondary};
    font-size: 12px;

    code {
      color: ${token.colorText};
      font-family: inherit;
      white-space: nowrap;
    }

    @media (max-width: 900px) {
      grid-template-columns: 1fr 1fr;

      > span:first-child {
        grid-column: 1 / -1;
      }
    }
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
