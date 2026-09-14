# DSL 导入校验修复

这是 `admin-dsl-import-patch.zip` 的增量补丁。先确保上一版补丁已经执行，然后将本压缩包解压到 `spring-ai-alibaba-admin`，在该目录的 PowerShell Terminal 执行：

```powershell
.\patch-dsl-validation-fix\Apply.ps1
```

脚本只校验并原子替换两个前端源文件，不安装依赖、不构建、不启动进程、不修改配置。目标文件不是上一版或本版时，会在替换任何文件前停止。

修复内容：导入前读取本地 LLM 模型列表，按 Dify 模型名精确匹配并写入真实 `model_id/provider`；未配置模型时停止导入并显示模型名。为缺少 user/system 消息的 Dify LLM 补齐 Studio 必填提示词。将 Dify 代码节点输入输出变量从下划线格式转换为字母数字驼峰格式，并同步修改 Python/JavaScript 代码和节点引用。修正 `startId + sys.query` 引用以及 Dify memory 开关映射。

已经使用用户提供的生产 DSL 验证：65 个节点、71 条连线转换成功，LLM/分类器均有模型 ID，LLM 两类提示词非空，所有代码节点输入输出变量均符合字母数字规则。

执行后需重新构建前端。此前已经导入的草稿不会自动迁移，请删除旧草稿并重新导入 DSL。本地模型管理中必须存在 DSL 使用的 `deepseek-v3` 和 `qwen3.6-plus`；否则导入会明确提示缺失模型。
