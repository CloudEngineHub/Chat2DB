import React from 'react';
import type { IChatContextReference } from '@/service/aiStream';

export interface SuggestionItem {
  label: string;
  value: string;
  kind: 'table' | 'context';
  tableType?: string;
  tableName?: string;
  contextReference?: IChatContextReference;

  icon?: React.ReactNode;
  children?: SuggestionItem[];
  extra?: React.ReactNode;
  preview?: React.ReactNode;
}
export type SuggestionItems = SuggestionItem[];
