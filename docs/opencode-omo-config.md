# OpenCode / OMO 配置迁移说明

本次改造把原来设置页里的 `Oh My OpenCode / Oh My OpenCode Slim` 专属开关，统一迁移到了 OpenCode 的 Provider 配置流中。

## 新入口

- 普通 OpenCode provider：在 Provider 管理页里新增或编辑 `OpenCode`，选择 `普通 OpenCode`
- OMO / OMO Slim：在 Provider 管理页里新增或编辑 `OpenCode`，选择 `Oh My OpenCode` 或 `Oh My OpenCode Slim`

设置页不再提供 OMO / Slim 的独立入口，只保留版本检测、安装命令、语言和 GitHub Token。

## 普通 OpenCode provider

- 内部仍使用 `Provider.id(UUID)` 作为持久化主键
- live `opencode.json -> provider` 的 key 改为单独的 `providerKey`
- 保存普通 OpenCode provider 时，会按 additive 语义同步到 `opencode.json.provider[providerKey]`
- 删除 provider 时，会同步移除对应的 `providerKey`
- 列表页状态显示为 `已同步 / 未同步`
- 列表页操作显示为 `同步 / 移除同步`

## OMO / OMO Slim

- OMO / Slim 作为 OpenCode 的特殊 `category`
- Provider 记录中只保存草稿：`agents`、`categories`、`otherFields`
- 点击列表页 `应用` 后，才会生成本地 `oh-my-opencode*.json`
- 应用时会同步维护 `opencode.json.plugin`
- 标准版与 Slim 版保持互斥：同一时刻只应用一个
- 点击 `停用` 只移除 live 文件和 plugin，不删除 Provider 草稿
- 列表页状态显示为 `已应用 / 未应用`
- 列表页操作显示为 `应用 / 停用`

## 迁移兼容

- 旧 OpenCode provider 若缺少 `providerKey`，会在读取时自动补齐
- 旧设置页已经启用的 OMO / Slim，如果本地文件与 plugin 仍存在，会自动导入为新的 OpenCode Provider 记录

## 表单差异

### 普通 OpenCode

- 支持 `providerKey`
- 支持 `npm / apiKey / baseURL / models / extraOptions`
- `extraOptions` 会优先按 JSON 值解析
- `models` 支持模型名与通用 `options`
- 预览区支持普通 OpenCode JSON 双向编辑

### OMO / OMO Slim

- 标准版内置 main/sub agents 与 categories
- Slim 版内置 main/sub agents，不带 categories
- 支持自定义 agents / categories
- 支持 advanced JSON 与顶层 `otherFields`
- 支持从本地 `.json/.jsonc` 导入
- 支持按推荐模型一键填充
- 预览区显示合并后的只读 OMO JSON

## 验证建议

- 升级已有 OpenCode provider 后，确认 `providerKey` 已补齐且不会覆盖其他 provider
- 新增普通 OpenCode provider 后，只同步自身到 live `opencode.json`
- 保存 OMO 草稿后，确认不会立刻生成本地 OMO 文件
- 点击 `应用` / `停用` 时，确认 plugin 与 `oh-my-opencode*.json` 会正确互斥切换
