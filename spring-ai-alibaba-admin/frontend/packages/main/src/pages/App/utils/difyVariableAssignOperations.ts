type Obj = Record<string, any>;

export type ArithmeticAssignOperation = '+=' | '-=' | '*=' | '/=';

const ARITHMETIC_OPERATIONS = new Set<ArithmeticAssignOperation>([
  '+=',
  '-=',
  '*=',
  '/=',
]);

export interface DifyArithmeticAssignmentMap {
  [nodeId: string]: Record<number, ArithmeticAssignOperation>;
}

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));

/**
 * The existing Studio Dify importer only accepts over-write / set / clear.
 *
 * For arithmetic operations we temporarily normalize them to a supported operation,
 * let importWorkflowDsl perform all variable selector/type conversions, then restore
 * the arithmetic operator onto the generated Studio VariableAssign input item.
 */
export function prepareDifyArithmeticAssignments(document: unknown): {
  document: unknown;
  operations: DifyArithmeticAssignmentMap;
} {
  if (!document || typeof document !== 'object') {
    return { document, operations: {} };
  }

  const copied = clone(document as Obj);
  const graph = copied?.workflow?.graph;
  const operations: DifyArithmeticAssignmentMap = {};

  if (!graph || !Array.isArray(graph.nodes)) {
    return { document: copied, operations };
  }

  for (const node of graph.nodes) {
    const data = node?.data;
    if (!data || !['assigner', 'variable-assigner'].includes(data.type)) {
      continue;
    }

    if (!Array.isArray(data.items)) continue;

    data.items.forEach((item: Obj, index: number) => {
      const operation = item?.operation as ArithmeticAssignOperation;
      if (!ARITHMETIC_OPERATIONS.has(operation)) return;

      if (!operations[node.id]) operations[node.id] = {};
      operations[node.id][index] = operation;

      // Keep the original value/input selector untouched.
      // This only bypasses the old importer's operation whitelist.
      item.operation = item.input_type === 'variable' ? 'over-write' : 'set';
    });
  }

  return { document: copied, operations };
}

export function restoreStudioArithmeticAssignments<T extends Obj>(
  imported: T,
  operations: DifyArithmeticAssignmentMap,
): T {
  const nodes = imported?.config?.nodes;
  if (!Array.isArray(nodes)) return imported;

  for (const node of nodes) {
    const nodeOperations = operations[node.id];
    if (!nodeOperations) continue;

    const inputs = node?.config?.node_param?.inputs;
    if (!Array.isArray(inputs)) continue;

    Object.entries(nodeOperations).forEach(([indexText, operation]) => {
      const index = Number(indexText);
      if (!Number.isInteger(index) || !inputs[index]) return;
      inputs[index].operation = operation;
    });
  }

  return imported;
}
