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

import com.alibaba.cloud.ai.studio.core.base.manager.ProviderManager;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Manages external YAML definitions; does not change registered model snapshots. */
@Service
public class ModelYamlFileService {
    @Value("${saa.model.directory:./model}") private String directory = "./model";
    @Resource private ProviderManager providerManager;
    public record FileContent(String filename, String content, String version) { }
    public record FileInfo(String filename, List<String> models, int parameter_count, String version,
            long modified, String error) { }

    private Path providerRoot(String provider) throws IOException {
        var context = RequestContextHolder.getRequestContext();
        if (context == null || context.getWorkspaceId() == null || context.getWorkspaceId().isBlank())
            throw new IllegalArgumentException("缺少工作空间上下文");
        providerManager.getProviderDetail(provider, true);
        if (provider == null || provider.isBlank() || provider.equals(".") || provider.equals("..")
                || provider.contains("/") || provider.contains("\\")) throw new IllegalArgumentException("无效供应商标识");
        if (directory == null || directory.isBlank()) throw new IllegalArgumentException("请先配置 saa.model.directory 外部模型目录");
        Path base = Path.of(directory.trim()).toAbsolutePath().normalize();
        Files.createDirectories(base); base = base.toRealPath();
        Path root = base.resolve(provider);
        if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("供应商目录不能是符号链接");
        Files.createDirectories(root);
        return root;
    }
    private Path target(Path root, String filename) throws IOException {
        if (filename == null || filename.length() > 512 || filename.contains("\\")
                || !filename.toLowerCase(Locale.ROOT).matches(".*\\.ya?ml$"))
            throw new IllegalArgumentException("文件名必须以 .yaml 或 .yml 结尾");
        Path relative = Path.of(filename);
        if (relative.isAbsolute()) throw new IllegalArgumentException("不能使用绝对路径");
        for (Path part : relative) if (part.toString().equals("..") || part.toString().equals("."))
            throw new IllegalArgumentException("文件名不能包含 . 或 .. 路径段");
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new IllegalArgumentException("文件不能超出供应商目录");
        Path current = root;
        for (Path part : root.relativize(result)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("不能操作符号链接文件或目录");
        }
        return result;
    }
    private List<Path> files(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            List<Path> result = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).matches(".*\\.ya?ml$")).sorted().limit(501).toList();
            if (result.size() > 500) throw new IllegalArgumentException("每个供应商最多管理 500 个 YAML 文件");
            return result;
        }
    }
    private byte[] bytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] result = input.readNBytes(ModelYamlParser.MAX_FILE_BYTES + 1);
            if (result.length > ModelYamlParser.MAX_FILE_BYTES) throw new IllegalArgumentException("单个 YAML 文件不能超过 1 MiB");
            return result;
        }
    }
    private static String version(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ModelYamlParser.Parsed parse(String filename, byte[] data, String provider) {
        var parsed = ModelYamlParser.parse(List.of(new ModelYamlParser.Source(filename, data)));
        for (var definition : parsed.models()) {
            Object declared = definition.document().get("provider");
            if (declared != null && !provider.equals(String.valueOf(declared)))
                throw new IllegalArgumentException("YAML 的 provider 必须与当前供应商标识一致，或移除此字段");
        }
        return parsed;
    }
    public synchronized List<FileInfo> list(String provider) throws IOException {
        Path root = providerRoot(provider); List<FileInfo> result = new ArrayList<>();
        for (Path file : files(root)) {
            String name = root.relativize(file).toString().replace('\\','/');
            byte[] data = bytes(file); List<String> models = List.of(); int count = 0; String error = null;
            try {
                var parsed = parse(name, data, provider); models = parsed.models().stream().map(ModelYamlParser.Definition::modelId).toList();
                count = parsed.models().stream().mapToInt(d -> d.rules().size()).sum();
            } catch (IllegalArgumentException invalid) { error = invalid.getMessage(); }
            result.add(new FileInfo(name, models, count, version(data), Files.getLastModifiedTime(file).toMillis(), error));
        }
        return result;
    }
    public synchronized FileContent read(String provider, String filename) throws IOException {
        Path root = providerRoot(provider); Path file = target(root,filename); byte[] data = bytes(file);
        return new FileContent(filename, new String(data, StandardCharsets.UTF_8), version(data));
    }
    private void checkVersion(Path file, String expected) throws IOException {
        if (expected == null || expected.isBlank() || !version(bytes(file)).equals(expected))
            throw new IllegalArgumentException("文件已被修改，请刷新后重新操作");
    }
    public synchronized FileContent save(String provider, String filename, String content, String expected, boolean create) throws IOException {
        Path root = providerRoot(provider); Path file = target(root,filename);
        if (content == null) throw new IllegalArgumentException("YAML 内容不能为空");
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        var parsed = parse(filename, data, provider);
        if (create && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("同名文件已存在，请使用编辑功能");
        if (!create) checkVersion(file, expected);
        Set<String> ids = new HashSet<>(); parsed.models().forEach(d -> ids.add(d.modelId()));
        List<Path> existing = files(root);
        if (create && existing.size() >= 500) throw new IllegalArgumentException("每个供应商最多管理 500 个 YAML 文件");
        for (Path other : existing) {
            if (other.equals(file)) continue;
            ModelYamlParser.Parsed definitions;
            try { definitions = parse(other.getFileName().toString(),bytes(other),provider); }
            catch (IllegalArgumentException invalid) { continue; }
            for (var definition : definitions.models()) if (ids.contains(definition.modelId()))
                throw new IllegalArgumentException("模型 " + definition.modelId() + " 已在文件 " + root.relativize(other) + " 中配置");
        }
        Files.createDirectories(file.getParent()); Path temp = Files.createTempFile(file.getParent(),".model-", ".tmp");
        try {
            Files.write(temp,data);
            if (create) Files.move(temp,file);
            else {
                try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            }
        } finally { Files.deleteIfExists(temp); }
        return new FileContent(filename,content,version(data));
    }
    public synchronized boolean delete(String provider, String filename, String expected) throws IOException {
        Path root = providerRoot(provider); Path file = target(root,filename); checkVersion(file,expected); Files.delete(file); return true;
    }
}
