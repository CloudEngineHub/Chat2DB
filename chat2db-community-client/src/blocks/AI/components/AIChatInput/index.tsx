import React, {
  memo,
  useState,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
  useEffect,
  useRef,
  useCallback,
} from 'react';
import { Input } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { ChatSourceType, QuestionType } from '@/constants/chat';
import { PromptTableVO } from '@/typings/chat';
import { DatabaseTypeCode } from '@/constants';

import i18n from '@/i18n';
import AICascaderSource, { IAICascaderData } from '../AICascaderSource';
import AIAtMetion from '../AIAtMetion';
import { SuggestionItem } from '../AIAtMetion/interface';
import AIModelSelect from '../AIModelSelect';
import sqlService from '@/service/sql';
import { ITable } from '@/typings';
import { useGlobalStore } from '@/store/global';
import { useStyles } from './style';
import { keyboardKey } from '@/utils';
import { useAIStore } from '@/store/ai';
import { ErrorCode } from '@/constants/request';

import { TextAreaRef } from 'antd/es/input/TextArea';
import { PageType } from '@/store/ai/slices/cascader/initialState';
import { debounce } from 'lodash';
import { IconButton } from '@chat2db/ui';
import aiAttachmentService, { IChatAttachment } from '@/service/aiAttachment';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';
import feedback from '@/utils/feedback';
import type { IChatContextReference } from '@/service/aiStream';
import clientExtension from '@client-extension';
import type {
  AiContextMentionCandidate,
  AiContextMentionRequest,
} from '@/client-extension/types';
import {
  detectMentionTrigger,
  filterUnselectedMentionCandidates,
  normalizeMentionInput,
  reconcileSelectedMentions,
  replaceMentionTrigger,
  shouldOpenMention,
  upsertSelectedMention,
  type MentionTrigger,
  type SelectedMention,
} from './mentionSelection';

export interface SendParams {
  input: string;
  questionType: QuestionType;
  source: ChatSourceType;
  // database information
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  databaseType?: DatabaseTypeCode;
  tableName?: string;
  // selected table
  tableList?: PromptTableVO[];
  // model
  model?: string;

  // optimize sql, selected sql
  sql?: string;

  attachments?: IChatAttachment[];
  contextReferences?: IChatContextReference[];
}

interface ChatInputProps {
  className?: string;
  chatInputAreaClassName?: string;
  loading?: boolean;
  contextInfo?: IAICascaderData;
  onContextChange?: (contextInfo: IAICascaderData) => void;
  // Whether to clear the input box after sending
  clearAfterSend?: boolean;
  inputRightAddons?: React.ReactNode;
  // Hide selection database
  hideDatabaseSelect?: boolean;
  modelOptions?: Array<{ label: string; value: string; isDefault?: boolean }>;
  showCustomModelEntry?: boolean;
  onCustomModelClick?: () => void;
  customModelText?: string;
  prefillInputState?: { text: string; token: number; questionType?: QuestionType } | null;
  onChatSend?: (param: SendParams) => void;
  onStop?: () => void;
  autoSize?: boolean | { minRows?: number; maxRows?: number };
  autoFocus?: boolean;
}

export interface ChatInputPropsRef {
  triggerSend: (params: SendParams) => void;
  setQuestionType: (value: QuestionType) => void;
  focusInput: () => void;
  resetAttachments: () => void;
  openAttachmentPicker: () => void;
}

const ATTACHMENT_ACCEPT = '.pdf,.doc,.docx,.md,.txt,.json,.csv,.xlsx,.xls';
const ATTACHMENT_FILE_TYPES = ['pdf', 'doc', 'docx', 'md', 'txt', 'json', 'csv', 'xlsx', 'xls'];
const ATTACHMENT_PARSE_MESSAGE_KEY = 'chat-attachment-parse';
const CONTEXT_MENTION_PAGE_SIZE = 20;

type ContextSearchRequest = Pick<AiContextMentionRequest, 'searchKey' | 'inputText'>;

interface ContextSearchCursor {
  contextInfo: IAICascaderData | null | undefined;
  request: ContextSearchRequest;
  requestSequence: number;
  pageNo: number;
  hasNextPage: boolean;
}

const toContextSuggestions = (candidates: readonly AiContextMentionCandidate[]): SuggestionItem[] =>
  candidates.map((candidate) => ({
    label: candidate.label,
    value: `context:${candidate.provider}:${candidate.type}:${candidate.id}`,
    kind: 'context',
    contextReference: {
      provider: candidate.provider,
      id: candidate.id,
      type: candidate.type,
      label: candidate.label,
      description: candidate.description,
    },
    icon: candidate.icon,
    extra: candidate.extra,
    preview: candidate.preview,
  }));

const AIChatInput = forwardRef((props: ChatInputProps, ref: ForwardedRef<ChatInputPropsRef>) => {
  const {
    className,
    chatInputAreaClassName,
    loading,
    hideDatabaseSelect,
    modelOptions,
    showCustomModelEntry,
    onCustomModelClick,
    customModelText,
    prefillInputState,
    onChatSend,
    onContextChange,
    onStop,
    clearAfterSend = true,
    autoSize,
    autoFocus = false,
  } = props;
  const { styles } = useStyles();
  const [inputValue, setInputValue] = useState('');
  const [prefillQuestionType, setPrefillQuestionType] = useState<QuestionType>();
  const [tableList, setTableList] = useState<ITable[]>([]);
  const [contextList, setContextList] = useState<SuggestionItem[]>([]);
  const [contextHasNextPage, setContextHasNextPage] = useState(false);
  const [contextLoadingMore, setContextLoadingMore] = useState(false);
  const [selectedMentions, setSelectedMentions] = useState<SelectedMention[]>([]);
  const [mentionTrigger, setMentionTrigger] = useState<MentionTrigger | null>(null);
  const [attachments, setAttachments] = useState<IChatAttachment[]>([]);
  const [attachmentLoading, setAttachmentLoading] = useState(false);
  const textareaRef = useRef<TextAreaRef>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const isComposingRef = useRef<boolean>(false); // IME input method combination status
  const contextRequestSequenceRef = useRef(0);
  const contextSearchCursorRef = useRef<ContextSearchCursor | null>(null);
  const contextLoadingSequenceRef = useRef<number | null>(null);

  // caches tables without search conditions
  const tableListWithoutSearchKey = useRef<ITable[]>([]);

  const { mainPageActiveTab } = useGlobalStore((state) => ({
    mainPageActiveTab: state.mainPageActiveTab,
  }));

  const { cascaderDataMap, setCascaderData, clearCascaderData } = useAIStore((state) => ({
    cascaderDataMap: state.cascaderDataMap,
    setCascaderData: state.setCascaderData,
    clearCascaderData: state.clearCascaderData,
  }));

  const focusInput = useCallback(() => {
    const textarea = textareaRef.current?.resizableTextArea?.textArea;
    if (!textarea) return;
    textarea.focus();
    const length = textarea.value.length;
    textarea.setSelectionRange(length, length);
  }, []);

  useImperativeHandle(ref, () => ({
    triggerSend,
    setQuestionType: setPrefillQuestionType,
    focusInput,
    resetAttachments: () => {
      setAttachments([]);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    },
    openAttachmentPicker: () => {
      handleAttachmentTrigger();
    },
  }));

  useEffect(() => {
    if (!autoFocus) return;

    const timer = window.setTimeout(() => {
      focusInput();
    }, 0);

    return () => {
      window.clearTimeout(timer);
    };
  }, [autoFocus, focusInput]);

  useEffect(() => {
    if (props.contextInfo) {
      setCascaderData(mainPageActiveTab as PageType, props.contextInfo ?? null);
    }
  }, [props.contextInfo, mainPageActiveTab]);

  useEffect(() => {
    if (!prefillInputState?.token) {
      return;
    }

    setInputValue(prefillInputState.text || '');
    setPrefillQuestionType(prefillInputState.questionType);
    window.setTimeout(() => {
      focusInput();
    }, 0);
  }, [focusInput, prefillInputState?.token]);

  useEffect(() => {
    tableListWithoutSearchKey.current = [];
    contextRequestSequenceRef.current += 1;
    setSelectedMentions([]);
    setMentionTrigger(null);
    setTableList([]);
    setContextList([]);
    setContextHasNextPage(false);
    contextSearchCursorRef.current = null;
    contextLoadingSequenceRef.current = null;
    setContextLoadingMore(false);
    if (cascaderDataMap[mainPageActiveTab]) {
      fetchTableList(cascaderDataMap[mainPageActiveTab], '');
    }
  }, [cascaderDataMap[mainPageActiveTab]]);

  const fetchTableList = useRef(
    debounce(async (_contextInfo: IAICascaderData, searchKey: string, requestSequence?: number) => {
      if (!_contextInfo) return;
      if (requestSequence !== undefined && requestSequence !== contextRequestSequenceRef.current) return;
      if ('dataSourceId' in _contextInfo && _contextInfo?.dataSourceId) {
        if (!searchKey && tableListWithoutSearchKey.current.length) {
          setTableList(tableListWithoutSearchKey.current);
          return;
        }
        let res;
        let viewRes;
        try {
          res = await sqlService.getTableList({
            dataSourceId: _contextInfo.dataSourceId,
            databaseName: _contextInfo.databaseName,
            schemaName: _contextInfo.schemaName,
            pageNo: 1,
            pageSize: 1000,
            searchKey,
          });
          viewRes = await sqlService.getViewList({
            dataSourceId: _contextInfo.dataSourceId,
            databaseName: _contextInfo.databaseName,
            schemaName: _contextInfo.schemaName,
            pageNo: 1,
            pageSize: 1000,
            searchKey,
          });
        } catch (error) {
          if (requestSequence !== undefined && requestSequence !== contextRequestSequenceRef.current) return;
          const requestError = error as { errorCode?: string };
          if (
            requestError.errorCode === 'QUERY_DATASOURCE_ERROR' ||
            requestError.errorCode === ErrorCode.NeedLoggedIn
          ) {
            tableListWithoutSearchKey.current = [];
            setTableList([]);
            clearCascaderData(mainPageActiveTab as PageType);
          }
          return;
        }

        if (requestSequence !== undefined && requestSequence !== contextRequestSequenceRef.current) return;

        const atTableList =
          res.data?.map((s) => ({
            ...s,
            tableType: 'TABLE',
          })) || [];

        const atViewList =
          viewRes.data?.map((s) => ({
            ...s,
            tableType: 'VIEW',
          })) || [];

        const list = [...atTableList, ...atViewList];

        if (!searchKey) {
          tableListWithoutSearchKey.current = list;
        }

        setTableList(list);
      }
    }, 300),
  ).current;

  useEffect(() => {
    return () => fetchTableList.cancel();
  }, []);

  const requestContextPage = (
    contextInfo: IAICascaderData | null | undefined,
    request: ContextSearchRequest,
    pageNo: number,
  ) => {
    if (!clientExtension.contextMentions) {
      return null;
    }
    const dataSourceId = contextInfo && 'dataSourceId' in contextInfo ? contextInfo.dataSourceId : undefined;
    const databaseName = contextInfo && 'databaseName' in contextInfo ? contextInfo.databaseName : undefined;
    const schemaName = contextInfo && 'schemaName' in contextInfo ? contextInfo.schemaName : undefined;
    return clientExtension.contextMentions({
      ...request,
      dataSourceId,
      databaseName,
      schemaName,
      pageNo,
      pageSize: CONTEXT_MENTION_PAGE_SIZE,
    });
  };

  const fetchContextList = async (
    contextInfo: IAICascaderData | null | undefined,
    request: ContextSearchRequest,
    requestSequence: number,
    onResolved: (hasCandidates: boolean) => void,
  ) => {
    const response = requestContextPage(contextInfo, request, 1);
    if (!response) {
      setContextList([]);
      setContextHasNextPage(false);
      onResolved(false);
      return;
    }
    try {
      const page = await response;
      if (requestSequence !== contextRequestSequenceRef.current) return;
      const suggestions = toContextSuggestions(page.data || []);
      const hasUnselectedCandidates = filterUnselectedMentionCandidates(suggestions, selectedMentions).length > 0;
      const hasNextPage = Boolean(page.hasNextPage);
      setContextList(suggestions);
      setContextHasNextPage(hasNextPage);
      contextSearchCursorRef.current = {
        contextInfo,
        request,
        requestSequence,
        pageNo: page.pageNo || 1,
        hasNextPage,
      };
      window.setTimeout(() => {
        if (requestSequence === contextRequestSequenceRef.current) {
          onResolved(hasUnselectedCandidates);
        }
      }, 0);
    } catch {
      if (requestSequence !== contextRequestSequenceRef.current) return;
      setContextList([]);
      setContextHasNextPage(false);
      contextSearchCursorRef.current = null;
      onResolved(false);
    }
  };

  useEffect(
    () => () => {
      contextRequestSequenceRef.current += 1;
    },
    [],
  );

  const handleSend = async (params?: SendParams) => {
    if (loading || attachmentLoading) return;

    /**
     * source parameter
     * workspace drawer: DATASOURCE_DRAWER_CHAT
     * dashboard drawer: DASHBOARD_DRAWER_CHAT
     * chat drawer: DRAWER_CHAT
     * console box: DATASOURCE_CONSOLE_CHAT
     *
     */
    let source = params?.source || ChatSourceType.DASHBOARD_DRAWER_CHAT;
    if (mainPageActiveTab === 'workspace') {
      source = ChatSourceType.DATASOURCE_DRAWER_CHAT;
    } else if (mainPageActiveTab === 'dashboard') {
      source = ChatSourceType.DASHBOARD_DRAWER_CHAT;
    } else if (mainPageActiveTab === 'chat' || mainPageActiveTab === 'stream') {
      source = ChatSourceType.DRAWER_CHAT;
    }

    /**
     * questionType parameter
     * default is ORDINARY_CHAT
     * console opens as NL_2_SQL
     */
    const questionType = params?.questionType || prefillQuestionType || QuestionType.ORDINARY_CHAT;

    const finalAttachments = params?.attachments ?? attachments;
    const rawInput = params?.input ?? inputValue;
    const trimmedInput = normalizeMentionInput(rawInput || '', selectedMentions).trim();
    const finalInput =
      trimmedInput ||
      (finalAttachments.length
        ? _contextHasDatabase(cascaderDataMap[mainPageActiveTab])
          ? '请结合已上传文件和当前数据库上下文进行联合分析，给出关键发现、验证思路和建议。'
          : '请基于已上传文件进行分析，给出摘要、关键发现、风险点和建议。'
        : '');

    if (!finalInput) return;

    const contextInfo = cascaderDataMap[mainPageActiveTab];
    const _contextInfo = contextInfo
      ? {
          ...contextInfo,
          dataSourceId: params?.dataSourceId || ('dataSourceId' in contextInfo ? contextInfo?.dataSourceId : undefined),
          databaseName: params?.databaseName || ('databaseName' in contextInfo ? contextInfo?.databaseName : undefined),
          schemaName: params?.schemaName || ('schemaName' in contextInfo ? contextInfo?.schemaName : undefined),
          tableName: params?.tableName ?? undefined,
        }
      : null;

    const _params = {
      ..._contextInfo,
      ...params,
      questionType,
      input: finalInput,
      source,
      model: useAIStore.getState().selectedModel?.value,
      tableList: selectedMentions
        .filter((mention) => mention.kind === 'table')
        .map((mention) => ({ tableName: mention.tableName, tableType: mention.tableType })) as any,
      contextReferences: selectedMentions
        .filter((mention) => mention.kind === 'context' && mention.contextReference)
        .map((mention) => mention.contextReference!),
      attachments: finalAttachments,
    };

    onChatSend?.(_params);

    setSelectedMentions([]);
    setPrefillQuestionType(undefined);
    setAttachments([]);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
    if (clearAfterSend) {
      setInputValue('');
    }
  };

  const triggerSend = (params: SendParams) => {
    if (loading) return;

    handleSend(params);
  };

  const handleChange = (value: string) => {
    setSelectedMentions((previous) => reconcileSelectedMentions(value, previous));
    setInputValue(value);

    if (!value) {
      setPrefillQuestionType(undefined);
      return;
    }
  };

  const _contextHasDatabase = (contextInfo?: IAICascaderData | null) => {
    if (!contextInfo) {
      return false;
    }
    return Boolean('dataSourceId' in contextInfo && contextInfo.dataSourceId);
  };

  const parseSelectedFiles = useCallback(
    async (selectedFiles: Array<{ file?: File; filePath?: string; fileName?: string }>) => {
      if (!selectedFiles.length) {
        return;
      }

      setAttachmentLoading(true);
      feedback.loading({
        content: i18n('stream.attachment.parsing'),
        key: ATTACHMENT_PARSE_MESSAGE_KEY,
        duration: 0,
      });

      try {
        const results = await Promise.allSettled(
          selectedFiles.map((item) =>
            aiAttachmentService.parseAttachment({
              file: item.file,
              filePath: item.filePath,
              fileName: item.fileName,
            }),
          ),
        );

        const parsedAttachments = results
          .filter((item): item is PromiseFulfilledResult<IChatAttachment> => item.status === 'fulfilled')
          .map((item) => item.value);

        console.log('[AI attachments] parsed result', {
          requestedCount: selectedFiles.length,
          successCount: parsedAttachments.length,
          attachments: parsedAttachments.map((attachment) => ({
            fileName: attachment.fileName,
            fileType: attachment.fileType,
            contentCategory: attachment.contentCategory,
            contentLength: attachment.contentLength,
            truncated: attachment.truncated,
            contentPreview: attachment.content?.slice(0, 200),
          })),
        });

        if (parsedAttachments.length) {
          setAttachments((prev) => {
            const next = [...prev];
            parsedAttachments.forEach((attachment) => {
              const duplicateIndex = next.findIndex(
                (item) => item.fileName === attachment.fileName && item.content === attachment.content,
              );
              if (duplicateIndex === -1) {
                next.push(attachment);
              }
            });
            return next;
          });
        }

        const failedCount = results.length - parsedAttachments.length;
        if (!parsedAttachments.length) {
          feedback.error({
            content: i18n('stream.attachment.parseFailed'),
            key: ATTACHMENT_PARSE_MESSAGE_KEY,
          });
          return;
        }

        if (failedCount > 0) {
          feedback.warning({
            content: i18n('stream.attachment.partialFailed', parsedAttachments.length),
            key: ATTACHMENT_PARSE_MESSAGE_KEY,
          });
          return;
        }

        feedback.destroy(ATTACHMENT_PARSE_MESSAGE_KEY);
      } catch {
        feedback.error({
          content: i18n('stream.attachment.parseFailed'),
          key: ATTACHMENT_PARSE_MESSAGE_KEY,
        });
      } finally {
        setAttachmentLoading(false);
      }
    },
    [],
  );

  const handleAttachmentTrigger = useCallback(() => {
    if (attachmentLoading || loading) {
      return;
    }

    if (isDesktop) {
      jcefApi
        .selectFile({
          fileTypeList: ATTACHMENT_FILE_TYPES,
          multiple: true,
        })
        .then((data) => {
          const selectedFiles =
            data?.map((item) => ({
              filePath: item.filePath,
              fileName: item.fileName,
            })) || [];
          return parseSelectedFiles(selectedFiles);
        })
        .catch(() => {
          feedback.error(i18n('stream.attachment.parseFailed'));
        });
      return;
    }

    fileInputRef.current?.click();
  }, [attachmentLoading, loading, parseSelectedFiles]);

  const handleFileInputChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFiles = Array.from(event.target.files || []).map((file) => ({
      file,
      fileName: file.name,
    }));
    await parseSelectedFiles(selectedFiles);
    event.target.value = '';
  };

  const removeAttachment = (index: number) => {
    setAttachments((prev) => prev.filter((_, currentIndex) => currentIndex !== index));
  };

  const removeSelectedMention = (value: string) => {
    setSelectedMentions((previous) => previous.filter((mention) => mention.value !== value));
  };

  const getMentionList = (info?: MentionTrigger) => {
    const availableContext = filterUnselectedMentionCandidates(contextList, selectedMentions);
    const tables: SuggestionItem[] = (tableList || []).map((table) => ({
      label: table.name,
      value: `table:${table.tableType}:${table.name}`,
      kind: 'table',
      tableName: table.name,
      tableType: table.tableType,
      extra: table.tableType === 'VIEW' ? '视图' : '表',
    }));
    if (info?.mode === 'natural') return availableContext;
    const candidates = [...tables, ...availableContext];
    if (!info?.query) return candidates;
    return candidates.filter((item) => item.label.toLowerCase().includes(info.query.toLowerCase()));
  };

  const loadMoreContext = async () => {
    const cursor = contextSearchCursorRef.current;
    if (!cursor?.hasNextPage || contextLoadingSequenceRef.current === cursor.requestSequence) {
      return;
    }
    contextLoadingSequenceRef.current = cursor.requestSequence;
    setContextLoadingMore(true);
    try {
      const nextPageNo = cursor.pageNo + 1;
      const response = requestContextPage(cursor.contextInfo, cursor.request, nextPageNo);
      if (!response) return;
      const page = await response;
      if (cursor.requestSequence !== contextRequestSequenceRef.current) return;
      const suggestions = toContextSuggestions(page.data || []);
      setContextList((previous) => {
        const merged = new Map(previous.map((item) => [item.value, item]));
        suggestions.forEach((item) => merged.set(item.value, item));
        return [...merged.values()];
      });
      const hasNextPage = Boolean(page.hasNextPage);
      setContextHasNextPage(hasNextPage);
      contextSearchCursorRef.current = {
        ...cursor,
        pageNo: page.pageNo || nextPageNo,
        hasNextPage,
      };
    } catch {
      // Keep the current page and allow another scroll attempt.
    } finally {
      if (contextLoadingSequenceRef.current === cursor.requestSequence) {
        contextLoadingSequenceRef.current = null;
        setContextLoadingMore(false);
      }
    }
  };

  const updateMentionSuggestions = (
    value: string,
    cursor: number,
    onTrigger: (info?: MentionTrigger | false) => void,
  ) => {
    const nextTrigger = detectMentionTrigger(value, cursor);
    if (!nextTrigger) {
      contextRequestSequenceRef.current += 1;
      setMentionTrigger(null);
      setContextList([]);
      setContextHasNextPage(false);
      contextSearchCursorRef.current = null;
      onTrigger(false);
      return;
    }

    const requestSequence = contextRequestSequenceRef.current + 1;
    contextRequestSequenceRef.current = requestSequence;
    onTrigger(false);
    setContextList([]);
    setTableList([]);
    contextSearchCursorRef.current = null;
    contextLoadingSequenceRef.current = null;
    setContextHasNextPage(false);
    setContextLoadingMore(false);
    setMentionTrigger(nextTrigger);
    const contextInfo = cascaderDataMap[mainPageActiveTab];

    if (nextTrigger.mode === 'explicit') {
      fetchTableList(contextInfo, nextTrigger.query, requestSequence);
      onTrigger(nextTrigger);
    } else if (!clientExtension.contextMentions) {
      setMentionTrigger(null);
      onTrigger(false);
      return;
    }

    fetchContextList(
      contextInfo,
      nextTrigger.mode === 'explicit'
        ? { searchKey: nextTrigger.query || undefined }
        : { inputText: nextTrigger.inputText.slice(-200) },
      requestSequence,
      (hasCandidates) => {
        if (requestSequence !== contextRequestSequenceRef.current) return;
        if (shouldOpenMention(nextTrigger, hasCandidates)) {
          onTrigger(nextTrigger);
        } else {
          setMentionTrigger(null);
          onTrigger(false);
        }
      },
    );
  };

  const isSameContextInfo = (prev: IAICascaderData, next: IAICascaderData) => {
    if (prev === next) {
      return true;
    }
    if (!prev || !next) {
      return !prev && !next;
    }

    return (
      prev.dataSourceId === next.dataSourceId &&
      prev.databaseName === next.databaseName &&
      prev.schemaName === next.schemaName
    );
  };

  const renderSelectedContext = (mention: SelectedMention) =>
    mention.contextReference
      ? clientExtension.renderContextReference?.(mention.contextReference) || mention.label
      : mention.label;

  return (
    <AIAtMetion<MentionTrigger>
      className={className}
      items={getMentionList}
      hasMore={contextHasNextPage}
      loadingMore={contextLoadingMore}
      onLoadMore={loadMoreContext}
      onSelect={(item) => {
        const textarea = textareaRef.current?.resizableTextArea?.textArea;
        const cursor = textarea?.selectionStart ?? inputValue.length;
        const activeTrigger = mentionTrigger || detectMentionTrigger(inputValue, cursor);
        const replacement = activeTrigger
          ? replaceMentionTrigger(inputValue, cursor, activeTrigger, item.label)
          : { value: `${inputValue}${item.label}`, cursor: inputValue.length + item.label.length };

        setInputValue(replacement.value);
        setSelectedMentions((previous) => {
          const nextMention: SelectedMention = {
            value: item.value,
            label: item.label,
            kind: item.kind,
            tableName: item.tableName,
            tableType: item.tableType,
            contextReference: item.contextReference,
          };
          return upsertSelectedMention(previous, nextMention);
        });
        contextRequestSequenceRef.current += 1;
        setMentionTrigger(null);
        setContextList([]);
        setContextHasNextPage(false);
        contextSearchCursorRef.current = null;
        window.setTimeout(() => {
          textarea?.focus();
          textarea?.setSelectionRange(replacement.cursor, replacement.cursor);
        }, 0);
      }}
    >
      {({ onTrigger, onKeyDown, isOpen }) => (
        <div className={`${styles.chatInputArea}${chatInputAreaClassName ? ` ${chatInputAreaClassName}` : ''}`}>
          <input
            ref={fileInputRef}
            type="file"
            accept={ATTACHMENT_ACCEPT}
            multiple
            className={styles.hiddenFileInput}
            onChange={handleFileInputChange}
          />
          {!!attachments.length && (
            <div className={styles.attachmentList}>
              {attachments.map((attachment, index) => (
                <div key={`${attachment.fileName}-${index}`} className={styles.attachmentItem}>
                  <span className={styles.attachmentName} title={attachment.fileName}>
                    {attachment.fileName}
                  </span>
                  <button
                    type="button"
                    className={styles.attachmentRemoveButton}
                    onClick={() => removeAttachment(index)}
                  >
                    <CloseOutlined />
                  </button>
                </div>
              ))}
            </div>
          )}
          {!!selectedMentions.some((mention) => mention.kind === 'context') && (
            <div className={styles.contextReferenceList}>
              {selectedMentions
                .filter((mention) => mention.kind === 'context')
                .map((mention) => (
                  <button
                    key={mention.value}
                    type="button"
                    className={styles.contextReferenceItem}
                    title={mention.contextReference?.description}
                    aria-label={`取消选择${mention.label}`}
                    onClick={() => removeSelectedMention(mention.value)}
                  >
                    <span>{renderSelectedContext(mention)}</span>
                    <CloseOutlined className={styles.contextReferenceRemoveIcon} />
                  </button>
                ))}
            </div>
          )}
          <Input.TextArea
            ref={textareaRef}
            className={styles.textarea}
            placeholder={loading ? undefined : i18n('ai.input.placeholder', `${keyboardKey.command} + K`)}
            value={inputValue}
            disabled={loading || attachmentLoading}
            autoSize={autoSize ?? { minRows: 1, maxRows: 8 }}
            onChange={(e) => {
              const value = e.target.value;
              handleChange(value);
              if (!isComposingRef.current) {
                updateMentionSuggestions(value, e.target.selectionStart, onTrigger);
              }
            }}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={(e) => {
              isComposingRef.current = false;
              updateMentionSuggestions(e.currentTarget.value, e.currentTarget.selectionStart, onTrigger);
            }}
            onClick={(e) => {
              updateMentionSuggestions(e.currentTarget.value, e.currentTarget.selectionStart, onTrigger);
            }}
            onKeyDown={(e) => {
              if (isOpen && ['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft', 'Enter', 'Escape'].includes(e.key)) {
                onKeyDown(e);
                if (e.defaultPrevented) return;
              }

              // Enter sends, Shift+Enter inserts a newline, and IME composition does not send.
              if (e.key === 'Enter' && !e.shiftKey && !isComposingRef.current && !loading) {
                e.preventDefault();
                handleSend();
              }
            }}
            onKeyUp={(e) => {
              if (!isOpen && !isComposingRef.current && ['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) {
                updateMentionSuggestions(e.currentTarget.value, e.currentTarget.selectionStart, onTrigger);
              }
            }}
          />
          <div className={styles.bottomAddonsRow}>
            <div className={styles.bottomAddonsLeft}>
              {!hideDatabaseSelect && (
                <AICascaderSource
                  contextInfo={cascaderDataMap[mainPageActiveTab]}
                  onFileSelect={handleAttachmentTrigger}
                  onChange={(data) => {
                    const prevContext = cascaderDataMap[mainPageActiveTab];
                    if (isSameContextInfo(prevContext, data)) {
                      return;
                    }
                    setCascaderData(mainPageActiveTab as PageType, data);
                    onContextChange?.(data);
                  }}
                />
              )}
            </div>
            <div className={styles.bottomAddonsRight}>
              <AIModelSelect
                options={modelOptions}
                showCustomModelEntry={showCustomModelEntry}
                onCustomModelClick={onCustomModelClick}
                customModelText={customModelText}
              />
              {loading ? (
                <IconButton
                  size={{
                    boxSize: 30,
                    iconSize: 22,
                  }}
                  code="icon-chat-stop"
                  className={styles.stopButton}
                  onClick={onStop}
                />
              ) : (
                <IconButton
                  size={{
                    boxSize: 30,
                    iconSize: 22,
                  }}
                  code="icon-chat-send"
                  className={styles.sendButton}
                  disabled={!inputValue.trim() && !attachments.length}
                  onClick={() => handleSend()}
                />
                // <Button
                //   type="primary"
                //   size="small"
                //   shape="circle"
                //   className={styles.sendButton}
                //   icon={<ArrowUpOutlined />}
                //   disabled={!inputValue.trim()}
                //   onClick={() => handleSend()}
                // />
              )}
            </div>
          </div>
        </div>
      )}
    </AIAtMetion>
  );
});

export default memo(AIChatInput);
