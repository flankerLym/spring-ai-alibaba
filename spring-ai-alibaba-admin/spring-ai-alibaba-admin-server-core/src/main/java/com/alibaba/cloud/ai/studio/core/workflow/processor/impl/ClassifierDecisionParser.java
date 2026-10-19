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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, shared response parsing for both classifier modes. */
final class ClassifierDecisionParser {

	private static final Pattern DECISION = Pattern.compile(
			"<Decision>\\s*[：:]?\\s*", Pattern.CASE_INSENSITIVE);

	private static final Pattern INDEX = Pattern.compile("(?:([0-9]+)|\\[\\s*([0-9]+)\\s*\\])");

	private ClassifierDecisionParser() {
	}

	static String parse(String response, int categoryCount) {
		if (response == null) {
			throw new IllegalArgumentException("empty response");
		}
		String value = response.trim();
		Matcher marker = DECISION.matcher(value);
		if (marker.find()) {
			value = value.substring(marker.end()).trim();
		}
		value = value.replaceFirst("(?is)^```(?:json|text)?\\s*", "")
				.replaceFirst("(?is)\\s*```$", "").trim();
		value = value.replaceFirst("(?is)\\s*(?:</Decision>)?\\s*(?:---)?\\s*$", "").trim();
		Matcher index = INDEX.matcher(value);
		if (!index.matches()) {
			throw new IllegalArgumentException("expected exactly one integer category index");
		}
		try {
			int result = Integer.parseInt(index.group(1) != null ? index.group(1) : index.group(2));
			if (result < 1 || result > categoryCount) {
				throw new IllegalArgumentException("category index out of range");
			}
			return Integer.toString(result);
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("category index overflow", e);
		}
	}

	static String thought(String response) {
		Matcher decision = DECISION.matcher(response);
		if (!decision.find()) {
			return "";
		}
		return response.substring(0, decision.start())
				.replaceFirst("(?is)^\\s*<Thinking>\\s*[：:]?\\s*", "").trim();
	}

}
