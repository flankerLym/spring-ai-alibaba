# SAA LLM 结构化输出前端补丁

目标：给 `spring-ai-alibaba-admin` 的 LLM 节点补齐可视化结构化输出配置，直接复用后端已有的：

- `structured_output_enabled`
- `structured_output_schema`
- `structured_output`

## 修改文件

替换：
- `spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/nodes/LLM/panel.tsx`
- `spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/nodes/LLM/schema.tsx`

新增：
- `spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/nodes/LLM/structuredOutput.ts`
- `spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/components/StructuredOutputSchemaModal/index.tsx`
- `spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/components/StructuredOutputSchemaModal/index.module.less`

## 功能

1. LLM 输出区新增“结构化输出”开关。
2. 开启后显示 `structured_output object` 以及 Schema 字段预览。
3. “配置”打开 Schema 编辑弹窗。
4. 支持 Visual Editor 和 JSON Schema 两种编辑方式。
5. 支持 JSON 文件导入。
6. Visual Editor 支持：string/number/integer/boolean/object/array/enum、必填、描述、嵌套对象。
7. 保存后写入后端已支持的 `structured_output_enabled` / `structured_output_schema`。
8. 自动把 Schema 映射为 `output_params.structured_output.properties`，使下游节点可以选择并引用结构化输出字段。
9. 对 Dify DSL 已导入但 `output_params` 未同步的节点，在打开 LLM 配置面板时自动补齐输出变量树。
10. 切换模型（含推理模型）时不会丢失 `structured_output` 输出定义。

## 应用

在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\structured-output-ui-patch\Apply.ps1
```

然后进入：

```powershell
cd spring-ai-alibaba-admin\frontend
npm install
cd packages\main
npm run dev
```

项目当前 package.json 使用 npm workspace；也可以在 frontend 根目录执行 `npm run build:subtree:java` 做完整构建验证。
