/** Match the selected output handle as well as the target (targets can be shared). */
export function isSelectedBranchEdge(
  edge: { source: string; target: string; sourceHandle?: string | null },
  result?: {
    is_multi_branch?: boolean;
    multi_branch_results?: { condition_id: string; target_ids: string[] }[];
  },
): boolean | undefined {
  if (!result?.is_multi_branch) return undefined;
  return (result.multi_branch_results || []).some((branch) => {
    if (!branch.target_ids?.includes(edge.target)) return false;
    let ids: string[] = [branch.condition_id];
    const value = branch.condition_id || '';
    // Older classifier responses used Java List.toString(): [id] or [id1, id2].
    if (value.startsWith('[') && value.endsWith(']')) {
      try {
        const parsed: unknown = JSON.parse(value);
        ids = Array.isArray(parsed) ? parsed.map(String) : [];
      } catch {
        ids = value.slice(1, -1).split(',').map((id) => id.trim());
      }
    }
    return ids.some((id) => edge.sourceHandle === `${edge.source}_${id}`);
  });
}
