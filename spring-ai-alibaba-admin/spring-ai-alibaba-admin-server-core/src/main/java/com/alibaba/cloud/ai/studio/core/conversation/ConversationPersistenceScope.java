package com.alibaba.cloud.ai.studio.core.conversation;

/**
 * Marks the END-node persistence section.
 *
 * Existing workflow END logic still calls ChatMemory.add() when history is enabled.
 * During that call ChatMemory should only refresh Redis; the durable DB write is done
 * once by ConversationPersistenceAspect after END completes.
 */
public final class ConversationPersistenceScope {

	private static final ThreadLocal<Integer> END_DEPTH = ThreadLocal.withInitial(() -> 0);

	private ConversationPersistenceScope() {
	}

	public static void enterWorkflowEnd() {
		END_DEPTH.set(END_DEPTH.get() + 1);
	}

	public static void exitWorkflowEnd() {
		int depth = END_DEPTH.get() - 1;
		if (depth <= 0) {
			END_DEPTH.remove();
		}
		else {
			END_DEPTH.set(depth);
		}
	}

	public static boolean isWorkflowEnd() {
		return END_DEPTH.get() > 0;
	}

}
