import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { isSelectedBranchEdge } from '../src/utils/branch-edge.ts';

const edge = (id, target = 'shared') => ({ source: 'classifier', target, sourceHandle: `classifier_${id}` });
const result = (id, targets = ['shared']) => ({ is_multi_branch: true, multi_branch_results: [{ condition_id: id, target_ids: targets }] });

test('shared target highlights only the selected output handle', () => {
  assert.equal(isSelectedBranchEdge(edge('plan'), result('plan')), true);
  assert.equal(isSelectedBranchEdge(edge('other'), result('plan')), false);
  assert.equal(isSelectedBranchEdge(edge('empathy', 'llm'), result('plan')), false);
});
test('supports old Java list and JSON list response formats', () => {
  for (const id of ['[plan]', '["plan"]', '[plan, other]']) {
    assert.equal(isSelectedBranchEdge(edge('plan'), result(id)), true);
    assert.equal(isSelectedBranchEdge(edge('empathy'), result(id)), false);
  }
});
test('ordinary edges retain their status behavior', () => {
  assert.equal(isSelectedBranchEdge(edge('plan')), undefined);
  assert.equal(isSelectedBranchEdge(edge('plan'), { is_multi_branch: false }), undefined);
});
test('empty decisions, wrong targets and missing handles are not selected', () => {
  assert.equal(isSelectedBranchEdge(edge('plan'), { is_multi_branch: true }), false);
  assert.equal(isSelectedBranchEdge(edge('plan'), result('plan', ['llm'])), false);
  assert.equal(isSelectedBranchEdge({ source: 'classifier', target: 'shared' }, result('plan')), false);
});
test('target alone cannot activate the other port', () => {
  assert.equal(isSelectedBranchEdge(edge('other'), result('plan')), false);
  assert.equal(isSelectedBranchEdge(edge('other'), result('other')), true);
});
