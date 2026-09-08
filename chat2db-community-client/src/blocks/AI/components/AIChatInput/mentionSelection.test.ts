import assert from 'node:assert/strict';
import {
  detectMentionTrigger,
  filterUnselectedMentionCandidates,
  findNaturalFragmentRange,
  normalizeMentionInput,
  reconcileSelectedMentions,
  replaceMentionTrigger,
  shouldOpenMention,
  upsertSelectedMention,
  type SelectedMention,
} from './mentionSelection';

const contextMention = (id: string, type: string, label: string): SelectedMention => ({
  value: `context:catalog:${type}:${id}`,
  label,
  kind: 'context',
  contextReference: { provider: 'catalog', id, type, label, description: `${label} description` },
});

const selected = [contextMention('1', 'term', '销量'), contextMention('2', 'rule', '销量')];

assert.deepEqual(
  reconcileSelectedMentions('查询 @销量 ', selected).map(({ value }) => value),
  ['context:catalog:term:1'],
);
assert.deepEqual(reconcileSelectedMentions('查询 @销量 和 @销量 ', selected), selected);
assert.equal(
  normalizeMentionInput('查询 @三全水饺 的 @销量 ', [contextMention('3', 'term', '三全水饺'), selected[1]]),
  '查询 三全水饺 的 销量 ',
);
assert.equal(normalizeMentionInput('联系 test@example.com', selected), '联系 test@example.com');

assert.deepEqual(detectMentionTrigger('我要查询三全', 6), {
  mode: 'natural',
  query: '我要查询三全',
  inputText: '我要查询三全',
  start: 0,
  end: 6,
});
const punctuatedNaturalInput = '请查看生产工。';
assert.deepEqual(detectMentionTrigger(punctuatedNaturalInput, punctuatedNaturalInput.length), {
  mode: 'natural',
  query: '请查看生产工',
  inputText: punctuatedNaturalInput,
  start: 0,
  end: 6,
});
assert.equal(detectMentionTrigger('华东 ', 3), null);
assert.deepEqual(detectMentionTrigger('我要查询 @三全', 8), {
  mode: 'explicit',
  query: '三全',
  inputText: '我要查询 @三全',
  start: 5,
  end: 8,
});
assert.equal(detectMentionTrigger('联系 test@example.com', 19)?.mode, 'natural');
assert.equal(shouldOpenMention(detectMentionTrigger('@', 1)!, false), true);
assert.equal(shouldOpenMention(detectMentionTrigger('@orders', 7)!, false), true);
assert.equal(shouldOpenMention(detectMentionTrigger('统计销量', 4)!, false), false);
assert.equal(shouldOpenMention(detectMentionTrigger('统计销量', 4)!, true), true);
assert.deepEqual(findNaturalFragmentRange('我要查询三全', 6, '三全水饺'), { start: 4, end: 6 });
assert.deepEqual(findNaturalFragmentRange('统计', 2, '动销商品统计'), { start: 0, end: 2 });
assert.deepEqual(replaceMentionTrigger('我要查询三全', 6, detectMentionTrigger('我要查询三全', 6)!, '三全水饺'), {
  value: '我要查询三全水饺',
  cursor: 8,
});
assert.deepEqual(replaceMentionTrigger('统计', 2, detectMentionTrigger('统计', 2)!, '动销商品统计'), {
  value: '动销商品统计',
  cursor: 6,
});
assert.deepEqual(
  replaceMentionTrigger(
    '我要查询三全水饺的销量',
    11,
    detectMentionTrigger('我要查询三全水饺的销量', 11)!,
    '三全水饺',
  ),
  { value: '我要查询三全水饺的销量', cursor: 11 },
);
assert.deepEqual(
  replaceMentionTrigger('我要查询未知内容', 8, detectMentionTrigger('我要查询未知内容', 8)!, '三全水饺'),
  { value: '我要查询未知内容', cursor: 8 },
);
assert.deepEqual(replaceMentionTrigger('我要查询 @三全', 8, detectMentionTrigger('我要查询 @三全', 8)!, '三全水饺'), {
  value: '我要查询 三全水饺 ',
  cursor: 10,
});
assert.deepEqual(
  replaceMentionTrigger(
    punctuatedNaturalInput,
    punctuatedNaturalInput.length,
    detectMentionTrigger(punctuatedNaturalInput, punctuatedNaturalInput.length)!,
    '生产工单',
  ),
  { value: '请查看生产工单。', cursor: 7 },
);

const term = contextMention('3', 'term', '三全水饺');
const rule = contextMention('2', 'rule', '销量');
assert.deepEqual(upsertSelectedMention(upsertSelectedMention([], term), rule), [term, rule]);
assert.deepEqual(upsertSelectedMention([term, rule], term), [rule, term]);

const selectedMetric = contextMention('225', 'rule', '重点客户贡献率');
assert.deepEqual(
  filterUnselectedMentionCandidates(
    [
      { value: 'context:catalog:rule:225', label: '重点客户贡献率' },
      { value: 'context:catalog:template:262', label: '重点客户贡献率' },
      { value: 'context:catalog:term:209', label: '华东大区' },
    ],
    [selectedMetric],
  ),
  [{ value: 'context:catalog:term:209', label: '华东大区' }],
);
