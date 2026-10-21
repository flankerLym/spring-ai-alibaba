import { IBranchItem } from '@/types/work-flow';

// Some existing workflows accidentally store UUIDs as branch labels. Keep the
// real branch IDs untouched and only replace these opaque labels in the UI.
const UUID_LIKE_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const isOpaqueBranchLabel = (branch: IBranchItem): boolean => {
  const label = (branch.label || '').trim();
  return (
    !label ||
    label === branch.id ||
    UUID_LIKE_PATTERN.test(label) ||
    (label.length >= 32 && /^[0-9a-f-]+$/i.test(label))
  );
};

/**
 * Return a readable display label without changing the saved branch ID/label.
 * Human-authored labels are preserved; UUID-like labels become CASE N.
 */
export const getBranchDisplayLabel = (
  branch: IBranchItem,
  branches: IBranchItem[],
): string => {
  if (branch.id === 'default') return '默认';

  const caseIndex =
    branches
      .filter((item) => item.id !== 'default')
      .findIndex((item) => item.id === branch.id) + 1;

  if (isOpaqueBranchLabel(branch)) {
    return `CASE ${caseIndex > 0 ? caseIndex : 1}`;
  }

  return branch.label;
};
