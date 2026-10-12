/*
 * Copyright 2024-2026 the original author or authors.
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
package com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.converter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.alibaba.cloud.ai.studio.admin.builder.generator.model.Variable;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.VariableSelector;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.VariableType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.NodeType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.nodedata.StartNodeData;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.AbstractNodeDataConverter;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.DSLDialectType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.utils.MapReadUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import org.springframework.stereotype.Component;

@Component
public class StartNodeDataConverter extends AbstractNodeDataConverter<StartNodeData> {

	@Override
	public Boolean supportNodeType(NodeType nodeType) {
		return NodeType.START.equals(nodeType);
	}

	@Override
	protected List<DialectConverter<StartNodeData>> getDialectConverters() {
		return Stream.of(StartNodeDialectConverter.values()).map(StartNodeDialectConverter::dialectConverter).toList();
	}

	private enum StartNodeDialectConverter {

		DIFY(new DialectConverter<>() {
			@Override
			public Boolean supportDialect(DSLDialectType dialectType) {
				return DSLDialectType.DIFY.equals(dialectType);
			}

			@Override
			public StartNodeData parse(Map<String, Object> data) {
				List<Map<String, Object>> inputMap = data.get("variables") == null
                        ? List.of() : (List<Map<String, Object>>) data.get("variables");
				List<StartNodeData.StartInput> startInputs = new ArrayList<>();
				List<Variable> outputs = new ArrayList<>();
				List<VariableSelector> inputs = new ArrayList<>();
				ObjectMapper objectMapper = new ObjectMapper();
				objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
				objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
				for (Map<String, Object> variable : inputMap) {
					StartNodeData.StartInput startInput = objectMapper.convertValue(variable,
							StartNodeData.StartInput.class);
                    VariableType varType = startType(startInput.getType());
                    outputs.add(new Variable(startInput.getVariable(), varType)
                            .setDescription(startInput.getLabel())
                            .setValue(startInput.getDefaultValue())
                            .setExtraProperties(inputProperties(startInput)));
					startInputs.add(startInput);
					inputs.add(new VariableSelector("", startInput.getVariable(), startInput.getLabel()));
				}
				return new StartNodeData(inputs, outputs).setStartInputs(startInputs);
			}

			@Override
			public Map<String, Object> dump(StartNodeData nodeData) {
				ObjectMapper objectMapper = new ObjectMapper();
				objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
				objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
				Map<String, Object> data = new HashMap<>();
				data.put("variables", objectMapper.convertValue(nodeData.getStartInputs(), List.class));
				return data;
			}
		}), STUDIO(new DialectConverter<>() {

			@Override
			public Boolean supportDialect(DSLDialectType dialectType) {
				return DSLDialectType.STUDIO.equals(dialectType);
			}

			@Override
			public StartNodeData parse(Map<String, Object> data) throws JsonProcessingException {
				// 获取output属性
				List<?> outputList = MapReadUtil.getMapDeepValue(data, List.class, "config", "output_params");
				// 转换为Variable
				List<Variable> outputs = Stream.ofNullable(outputList)
					.flatMap(List::stream)
					.map(MapReadUtil::safeCastToMapWithStringKey)
					.map(mp -> new Variable(MapReadUtil.getMapDeepValue(mp, String.class, "key"),
							VariableType.fromStudioValue(MapReadUtil.getMapDeepValue(mp, String.class, "type"))
								.orElse(VariableType.OBJECT))
                        .setDescription(MapReadUtil.getMapDeepValue(mp, String.class, "desc"))
                        .setValue(mp.containsKey("default_value") ? mp.get("default_value") : mp.get("defaultValue"))
                        .setExtraProperties(new HashMap<>(mp)))
					.toList();
				StartNodeData nodeData = new StartNodeData();
                nodeData.setOutputs(outputs);
                nodeData.setStartInputs(outputs.stream().map(output -> new StartNodeData.StartInput()
                        .setVariable(output.getName())
                        .setType(output.getValueType().difyValue())
                        .setLabel(output.getDescription())
                        .setRequired(Boolean.TRUE.equals(output.getExtraProperties().get("required")))
                        .setDefaultValue(output.getValue())).toList());
                return nodeData;
			}

			@Override
			public Map<String, Object> dump(StartNodeData nodeData) {
				throw new UnsupportedOperationException();
			}
		}), CUSTOM(AbstractNodeDataConverter.defaultCustomDialectConverter(StartNodeData.class));

		private final DialectConverter<StartNodeData> dialectConverter;

		public DialectConverter<StartNodeData> dialectConverter() {
			return dialectConverter;
		}

		StartNodeDialectConverter(DialectConverter<StartNodeData> dialectConverter) {
			this.dialectConverter = dialectConverter;
		}

	}

    private static VariableType startType(String type) {
        if (type == null) {
            return VariableType.STRING;
        }
        return switch (type) {
            case "text-input", "paragraph", "select" -> VariableType.STRING;
            case "checkbox", "boolean" -> VariableType.BOOLEAN;
            case "file-list" -> VariableType.ARRAY_FILE;
            default -> VariableType.fromDifyValue(type)
                    .or(() -> VariableType.fromStudioValue(type)).orElse(VariableType.OBJECT);
        };
    }

    private static Map<String, Object> inputProperties(StartNodeData.StartInput input) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("required", Boolean.TRUE.equals(input.getRequired()));
        properties.put("default_value", input.getDefaultValue());
        return properties;
    }

	@Override
	public String generateVarName(int count) {
		// 让输入变量名称为start_xxx，方便用户理解
		if (count == 1) {
			return "start";
		}
		return "startNode" + count;
	}

}
