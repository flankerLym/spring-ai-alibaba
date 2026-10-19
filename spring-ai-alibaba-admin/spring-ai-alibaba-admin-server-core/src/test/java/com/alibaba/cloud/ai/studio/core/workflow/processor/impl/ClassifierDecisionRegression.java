/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.studio.core.workflow.processor.impl;

/** Runnable without Maven or external services. */
public final class ClassifierDecisionRegression {

	public static void main(String[] args) {
		runChecks();
	}

	static void runChecks() {
		String[] valid = { "2", " 2\n", "<Decision>：2", "<Decision>: 2", "<Decision>2</Decision>",
				"<Thinking>：follow-up asks for a plan\n<Decision>：2", "[2]", "<Decision>: [ 2 ]",
				"```json\n2\n```", "<Decision>: 2\n---", "```text\n<Decision>: 2\n```" };
		for (String value : valid) {
			if (!"2".equals(ClassifierDecisionParser.parse(value, 3))) {
				throw new AssertionError("Incorrect decision for " + value);
			}
		}
		String[] invalid = { "", "0", "4", "-1", "2.0", "[1,2]", "2,3", "category 2", "2 because...",
				"<Decision>: 2\n<Decision>: 1", "999999999999999999999", "<Decision>: []" };
		for (String value : invalid) {
			try {
				ClassifierDecisionParser.parse(value, 3);
				throw new AssertionError("Accepted invalid response " + value);
			}
			catch (IllegalArgumentException expected) {
				// Invalid responses must fail instead of choosing an arbitrary branch.
			}
		}
		if (!"reason".equals(ClassifierDecisionParser.thought("<Thinking>: reason\n<Decision>: 2"))) {
			throw new AssertionError("ASCII thought marker was not parsed");
		}
		if (!"原因".equals(ClassifierDecisionParser.thought("<Thinking>：原因\n<Decision>：2"))) {
			throw new AssertionError("Chinese thought marker was not parsed");
		}
	}

}
