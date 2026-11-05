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
package com.alibaba.cloud.ai.studio.core.model.llm;

import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Reads Dify model definitions as data; never extracts ZIPs or executes provider Python. */
public final class ModelYamlParser {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_BATCH_BYTES = 10 * MAX_FILE_BYTES;
    public static final int MAX_MODELS = 200;
    public record Source(String filename, byte[] content) { }
    public record Definition(String modelId, List<ParameterRule> rules, Map<String, Object> document,
            String filename, String yaml, String sha256) { }
    public record Parsed(List<Definition> models, List<String> ignoredFiles) { }
    private static final Set<String> MANAGED = Set.of("model", "messages", "input", "stream",
            "result_format", "incremental_output", "multi_model");
    private ModelYamlParser() { }

    public static Parsed parse(List<Source> uploads) {
        if (uploads == null || uploads.isEmpty()) throw invalid("files", "请选择 YAML 或 ZIP 文件");
        List<Source> sources = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        int total = 0;
        for (Source upload : uploads) {
            if (upload.content().length > MAX_BATCH_BYTES) throw invalid(upload.filename(), "文件过大");
            if (upload.filename().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(upload.content()), StandardCharsets.UTF_8)) {
                    ZipEntry entry;
                    int entries = 0;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (++entries > 1000) throw invalid(upload.filename(), "ZIP 条目过多");
                        if (entry.isDirectory()) continue;
                        byte[] bytes = zip.readNBytes(MAX_FILE_BYTES + 1);
                        if (bytes.length > MAX_FILE_BYTES) throw invalid(entry.getName(), "YAML 超过 1 MiB");
                        total += bytes.length;
                        if (total > MAX_BATCH_BYTES || sources.size() >= MAX_MODELS) throw invalid(upload.filename(), "批量大小或文件数量超限");
                        if (!isYaml(entry.getName())) { ignored.add(entry.getName()); continue; }
                        sources.add(new Source(entry.getName(), bytes));
                    }
                }
                catch (IOException error) { throw invalid(upload.filename(), "无法读取 ZIP: " + error.getMessage()); }
            }
            else {
                if (!isYaml(upload.filename())) throw invalid(upload.filename(), "只支持 .yaml、.yml 或 .zip");
                if (upload.content().length > MAX_FILE_BYTES) throw invalid(upload.filename(), "YAML 超过 1 MiB");
                total += upload.content().length;
                if (total > MAX_BATCH_BYTES || sources.size() >= MAX_MODELS) throw invalid(upload.filename(), "批量大小或文件数量超限");
                sources.add(upload);
            }
        }
        List<Definition> definitions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Source source : sources) {
            String yaml;
            try { yaml = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source.content())).toString().replaceFirst("^\uFEFF", ""); }
            catch (CharacterCodingException error) { throw invalid(source.filename(), "必须使用 UTF-8 编码"); }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
            options.setCodePointLimit(MAX_FILE_BYTES); options.setNestingDepthLimit(30);
            try {
                for (Object value : new Yaml(new SafeConstructor(options)).loadAll(yaml)) {
                    if (value == null) continue;
                    List<?> documents = value instanceof List<?> list ? list : List.of(value);
                    for (Object item : documents) {
                        Map<String, Object> document = map(item, source.filename());
                        Definition definition = definition(document, source.filename(), yaml);
                        if (!ids.add(definition.modelId())) throw invalid(source.filename(), "同一批次重复模型: " + definition.modelId());
                        definitions.add(definition);
                        if (definitions.size() > MAX_MODELS) throw invalid(source.filename(), "最多导入 200 个模型");
                    }
                }
            }
            catch (IllegalArgumentException error) { throw error; }
            catch (RuntimeException error) { throw invalid(source.filename(), "YAML 解析失败: " + error.getMessage()); }
        }
        if (definitions.isEmpty()) throw invalid("files", "没有找到模型 YAML");
        return new Parsed(definitions, ignored);
    }

    private static Definition definition(Map<String, Object> doc, String filename, String yaml) {
        String id = Objects.toString(doc.get("model"), "").trim();
        if (id.isEmpty() || id.length() > 255 || id.chars().anyMatch(Character::isISOControl)) throw invalid(filename, "model 不能为空或包含控制字符");
        if (!"llm".equals(doc.get("model_type"))) throw invalid(filename, "仅支持 model_type: llm");
        if (!(doc.get("parameter_rules") instanceof List<?> rawRules) || rawRules.isEmpty()) throw invalid(filename, "parameter_rules 必须为非空数组");
        if (rawRules.size() > 200) throw invalid(filename, "参数数量超限");
        List<ParameterRule> rules = new ArrayList<>(); Set<String> codes = new HashSet<>();
        for (Object item : rawRules) {
            Map<String, Object> raw = map(item, filename);
            String code = Objects.toString(raw.getOrDefault("name", raw.get("code")), "");
            if (!code.matches("[A-Za-z][A-Za-z0-9_]*") || MANAGED.contains(code)) throw invalid(filename, "非法或执行链路管理的参数: " + code);
            if (!codes.add(code)) throw invalid(filename, "重复参数: " + code);
            Map<String, Object> normalized = template(Objects.toString(raw.get("use_template"), ""));
            normalized.putAll(raw);
            if (!normalized.containsKey("type")) throw invalid(filename, code + " 缺少 type 或受支持的 use_template");
            if (!Set.of("bool", "boolean", "int", "integer", "float", "double", "number", "numeric", "string", "text", "object", "dict", "map", "array", "list", "string_list").contains(String.valueOf(normalized.get("type")).toLowerCase(Locale.ROOT)))
                throw invalid(filename, code + " 的 type 不受支持");
            for (String bound : List.of("min", "max")) {
                if (normalized.containsKey(bound)) {
                    if (!(normalized.get(bound) instanceof Number)) throw invalid(filename, code + " 的 " + bound + " 必须为数字");
                    decimal(normalized.get(bound));
                }
            }
            if (normalized.containsKey("required") && !(normalized.get("required") instanceof Boolean)) throw invalid(filename, code + " 的 required 必须为布尔值");
            if (normalized.containsKey("options") && !(normalized.get("options") instanceof List<?>)) throw invalid(filename, code + " 的 options 必须为数组");
            normalized.put("code", code);
            ParameterRule rule = OfficialModelParameters.remoteRules(Map.of("parameter_rules", List.of(normalized))).get(0);
            if ("text".equals(normalized.get("type"))) rule.setType("String");
            if ("json_schema".equals(code)) rule.setType("Object");
            rule.setName(code).setSchema(raw);
            if (raw.get("help") instanceof Map<?, ?> help) rule.setHelp(strings(help));
            if (rule.getOptions() != null && rule.getDefaultValue() != null && !rule.getOptions().contains(rule.getDefaultValue())) {
                // Some Dify templates (e.g. source_lang=auto) omit their own default from options.
                List<Object> choices = new ArrayList<>(rule.getOptions()); choices.add(rule.getDefaultValue()); rule.setOptions(choices);
            }
            if (rule.getMin() != null && rule.getMax() != null && decimal(rule.getMin()).compareTo(decimal(rule.getMax())) > 0)
                throw invalid(filename, code + " 的 min 大于 max");
            if (rule.getDefaultValue() != null) validate(rule, rule.getDefaultValue());
            rules.add(rule);
        }
        String endpoint = Objects.toString(doc.get("endpoint_type"), "");
        if (!endpoint.isEmpty() && !Set.of("text-generation", "multimodal-generation").contains(endpoint)) throw invalid(filename, "endpoint_type 无效");
        try {
            return new Definition(id, rules, doc, filename, yaml,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml.getBytes(StandardCharsets.UTF_8))));
        }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static void validate(ParameterRule rule, Object value) {
        String code = rule.getCode();
        if (value == null) { if (rule.isRequired()) throw invalid(code, "必填参数不能为空"); return; }
        switch (rule.getType()) {
            case "Number" -> {
                if (!(value instanceof Number)) throw invalid(code, "必须为数字");
                BigDecimal number = decimal(value);
                if (Integer.valueOf(0).equals(rule.getPrecision()) && number.stripTrailingZeros().scale() > 0) throw invalid(code, "必须为整数");
                if (rule.getMin() != null && number.compareTo(decimal(rule.getMin())) < 0 || rule.getMax() != null && number.compareTo(decimal(rule.getMax())) > 0)
                    throw invalid(code, "超出 YAML 规定的范围");
            }
            case "Boolean" -> { if (!(value instanceof Boolean)) throw invalid(code, "必须为布尔值"); }
            case "String" -> { if (!(value instanceof String)) throw invalid(code, "必须为字符串"); }
            case "Object" -> { if (!(value instanceof Map<?, ?>) && !(value instanceof String)) throw invalid(code, "必须为 JSON 对象"); }
            default -> { if (rule.getType().startsWith("Array<") && !(value instanceof List<?>)) throw invalid(code, "必须为数组"); }
        }
        if (rule.getOptions() != null && !rule.getOptions().isEmpty()) {
            boolean valid = rule.getOptions().stream().anyMatch(option -> Objects.equals(value,
                    option instanceof Map<?, ?> map ? map.get("value") : option));
            if (!valid) throw invalid(code, "不在 YAML 选项中");
        }
    }
    private static BigDecimal decimal(Object number) {
        try { return new BigDecimal(number.toString()); }
        catch (NumberFormatException error) { throw invalid("number", "必须为有限数字"); }
    }
    private static Map<String, Object> template(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (Set.of("temperature", "top_p", "presence_penalty", "frequency_penalty", "repetition_penalty").contains(code)) {
            result.put("type", "float"); result.put("precision", 2);
            if (!"repetition_penalty".equals(code)) {
                result.put("min", code.endsWith("penalty") ? -2 : 0);
                result.put("max", "top_p".equals(code) ? 1 : 2);
            }
            result.put("default", "top_p".equals(code) || "temperature".equals(code) || "repetition_penalty".equals(code) ? 1 : 0);
        }
        else if (Set.of("max_tokens", "max_completion_tokens", "seed", "top_k").contains(code)) { result.put("type", "int"); result.put("precision", 0); }
        else if ("json_schema".equals(code)) result.put("type", "object");
        else if ("stop".equals(code)) result.put("type", "array");
        return result;
    }
    public static Map<String, Object> map(Object value, String source) {
        if (!(value instanceof Map<?, ?> map)) throw invalid(source, "必须为 YAML 对象");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> { if (!(key instanceof String)) throw invalid(source, "字段名必须是字符串"); result.put((String) key, item); });
        return result;
    }
    private static Map<String, String> strings(Map<?, ?> values) {
        Map<String, String> result = new LinkedHashMap<>(); values.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value))); return result;
    }
    private static boolean isYaml(String filename) { return filename.toLowerCase(Locale.ROOT).matches(".*\\.ya?ml$"); }
    private static IllegalArgumentException invalid(String source, String message) { return new IllegalArgumentException(source + ": " + message); }
}
