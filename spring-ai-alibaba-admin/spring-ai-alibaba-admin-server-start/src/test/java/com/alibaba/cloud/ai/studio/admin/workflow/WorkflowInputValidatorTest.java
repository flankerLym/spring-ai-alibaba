/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.studio.admin.workflow;

import com.alibaba.cloud.ai.studio.core.workflow.WorkflowInputValidator;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.CommonParam;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.workflow.nodedata.StartNodeData;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.converter.StartNodeDataConverter;
import com.alibaba.cloud.ai.studio.admin.builder.generator.service.dsl.DSLDialectType;
import com.alibaba.cloud.ai.studio.admin.builder.generator.model.VariableType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowInputValidatorTest {
    private CommonParam param(String type, Boolean required, Object defaultValue) {
        CommonParam param = new CommonParam();
        param.setKey("tk_sn");
        param.setType(type);
        param.setRequired(required);
        param.setDefaultValue(defaultValue);
        return param;
    }

    @Test
    void requiredMustBeSuppliedEvenWithDefault() {
        CommonParam param = param("String", true, "device");
        assertThrows(BizException.class, () -> WorkflowInputValidator.resolve(List.of(param), Map.of()));
        for (String text : List.of("", "   ")) {
            assertThrows(BizException.class,
                    () -> WorkflowInputValidator.resolve(List.of(param), Map.of("tk_sn", text)));
        }
        Map<String, Object> supplied = new HashMap<>();
        supplied.put("tk_sn", null);
        assertThrows(BizException.class, () -> WorkflowInputValidator.resolve(List.of(param), supplied));
    }

    @Test
    void optionalUsesDefaultOnlyOnOmission() {
        CommonParam param = param("String", false, "device");
        assertEquals("device", WorkflowInputValidator.resolve(List.of(param), Map.of()).get("tk_sn"));
        assertEquals("", WorkflowInputValidator.resolve(List.of(param), Map.of("tk_sn", "")).get("tk_sn"));
        Map<String, Object> supplied = new HashMap<>();
        supplied.put("tk_sn", null);
        assertNull(WorkflowInputValidator.resolve(List.of(param), supplied).get("tk_sn"));
        assertNull(supplied.get("tk_sn"));
    }

    @Test
    void legacyRequiredNullRemainsOptional() {
        assertFalse(WorkflowInputValidator.resolve(List.of(param("String", null, null)), Map.of())
                .containsKey("tk_sn"));
    }

    @Test
    void zeroFalseAndEmptyDefaultArePreserved() {
        assertEquals(0, WorkflowInputValidator.resolve(List.of(param("Number", true, null)),
                Map.of("tk_sn", 0)).get("tk_sn"));
        assertEquals(false, WorkflowInputValidator.resolve(List.of(param("Boolean", true, null)),
                Map.of("tk_sn", false)).get("tk_sn"));
        assertEquals(false, WorkflowInputValidator.resolve(List.of(param("Boolean", false, false)),
                Map.of()).get("tk_sn"));
        assertEquals("", WorkflowInputValidator.resolve(List.of(param("String", false, "")),
                Map.of()).get("tk_sn"));
    }

    @Test
    void declaredTypesRejectInvalidValuesAndArrayElements() {
        assertThrows(BizException.class, () -> WorkflowInputValidator.resolve(
                List.of(param("Number", false, null)), Map.of("tk_sn", "wrong")));
        assertThrows(BizException.class, () -> WorkflowInputValidator.resolve(
                List.of(param("String", false, null)), Map.of("tk_sn", false)));
        assertThrows(BizException.class, () -> WorkflowInputValidator.resolve(
                List.of(param("Array<Number>", false, null)), Map.of("tk_sn", List.of("wrong"))));
    }

    @Test
    void formValuesConvertToDeclaredTypes() {
        assertEquals(0, WorkflowInputValidator.resolve(List.of(param("Number", false, null)),
                Map.of("tk_sn", "0")).get("tk_sn"));
        assertEquals(false, WorkflowInputValidator.resolve(List.of(param("Boolean", false, null)),
                Map.of("tk_sn", "false")).get("tk_sn"));
        assertEquals(List.of(1, 2), WorkflowInputValidator.resolve(List.of(param("Array<Number>", false, "[1,2]")),
                Map.of()).get("tk_sn"));
    }

    @Test
    void defaultValueAliasesPreserveTypedValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String key : List.of("default", "default_value", "defaultValue")) {
            StartNodeData.StartInput input = mapper.readValue("{\"" + key + "\":false}", StartNodeData.StartInput.class);
            assertEquals(false, input.getDefaultValue());
            assertTrue(mapper.writeValueAsString(input).contains("\"default\":false"));
        }
    }

    @Test
    void commonParamAcceptsBothDefaultValueSpellings() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String key : List.of("default_value", "defaultValue")) {
            CommonParam input = mapper.readValue("{\"" + key + "\":0}", CommonParam.class);
            assertEquals(0, input.getDefaultValue());
        }
    }

    @Test
    void callerTypeCannotOverrideStartDefinition() {
        com.alibaba.cloud.ai.studio.core.workflow.WorkflowConfig config =
                new com.alibaba.cloud.ai.studio.core.workflow.WorkflowConfig();
        com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node node =
                new com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node();
        node.setType("Start");
        com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node.NodeCustomConfig nodeConfig =
                new com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node.NodeCustomConfig();
        com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node.OutputParam output =
                new com.alibaba.cloud.ai.studio.runtime.domain.workflow.Node.OutputParam();
        output.setKey("tk_sn");
        output.setType("String");
        nodeConfig.setOutputParams(List.of(output));
        node.setConfig(nodeConfig);
        config.setNodes(List.of(node));
        Object value = WorkflowInputValidator.readUserInput(config, "tk_sn", "String", false);
        assertEquals(false, value);
        assertThrows(BizException.class, () -> WorkflowInputValidator.resolve(
                List.of(output), Map.of("tk_sn", value)));
    }

    @Test
    void backendDifyImportRetainsConstraintsAndTextType() {
        StartNodeDataConverter converter = new StartNodeDataConverter();
        StartNodeData data = converter.parseMapData(Map.of("variables", List.of(Map.of(
                "variable", "user_stage", "type", "text-input", "label", "用户阶段",
                "required", false, "default", ""))), DSLDialectType.DIFY);
        assertEquals(1, data.getOutputs().size());
        assertEquals(VariableType.STRING, data.getOutputs().get(0).getValueType());
        assertEquals("", data.getStartInputs().get(0).getDefaultValue());
        assertEquals(false, data.getOutputs().get(0).getExtraProperties().get("required"));
        assertEquals("", data.getOutputs().get(0).getExtraProperties().get("default_value"));
    }
}
