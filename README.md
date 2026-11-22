# ChatStudy — Chat Patches 的翻译 / 朗读扩展

> **本项目是 [Chat Patches](https://github.com/mrbuilder1961/ChatPatches) 的 fork**，
> 在原模组基础上增加了**聊天翻译**与 **TTS 朗读**两大模块。
> Fork 已获得原作者许可。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62b47a)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Modloader-Fabric-eeeeee)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-LGPL--3.0-blue)](LICENSE)

---

## 这是什么

在 Chat Patches（强大的客户端聊天增强）之上，加了两个原版没有的能力：

| 功能 | 说明 |
|---|---|
| **聊天翻译** | 别人说的话自动翻成你的语言；你打的话自动翻成对方语言 |
| **TTS 朗读** | 把聊天内容念出来，支持系统语音 / GPT-SoVITS / Piper / Kokoro / OpenAI 兼容服务 |

### 翻译模块

- **85 种语言**（Minecraft 官方语言表与语言代码体系的交集），按使用人口三级分层
- **降级链**：首选源失败自动降级到下一个，每句独立重试
- **能力矩阵**：每个服务商支持哪些语言写在数据表里，不支持的直接跳过
- **占位符保护**：`@玩家名` / 坐标 / 命令 / 链接 / 数字 / 代码块不被翻译
- **六项质量校验**：数字一致性、目标文字系统、自我解释、退化重复等
- **定向判定**：按文字系统严格判断「这条要不要翻」，不做全语言猜测

支持的服务商：

| 服务商 | 类型 | 语言覆盖 |
|---|---|---|
| DeepSeek | OpenAI 兼容 | 85 种 |
| B站 Index-Translate | OpenAI 兼容（免费免 Key） | 85 种 |
| 混元 Hy-MT2 | OpenAI 兼容（本地） | 仅中/英 |
| Ollama / LM Studio / vLLM | OpenAI 兼容（本地） | 取决于模型 |
| MyMemory | GET 接口（免费） | 72 种 |

### 朗读模块

- 系统语音（离线）
- GPT-SoVITS（声音克隆）
- Piper（离线神经网络 TTS，30+ 语言）
- Kokoro（轻量开源）
- 任意 OpenAI 兼容 TTS

### 界面多语言

UI 覆盖 **17 种千万级人口语言**：

简体中文 / English / 日本語 / 한국어 / Русский / Español / Français / Deutsch /
Português / Italiano / العربية / हिन्दी / Bahasa Indonesia / Tiếng Việt / Türkçe /
Українська / Polski

跟随 Minecraft 客户端语言自动切换。显示名取自 MC 原生 `language.name`，不是硬编码翻译。

---

## 相比上游 Chat Patches 的改动

### 新增

- `translate/` — 完整翻译引擎（注册表、能力矩阵、降级链、占位符保护、质量校验）
- `tts/` — TTS 朗读引擎（5 种 provider）
- `chatstudy/` — 桥接层（把翻译/朗读接到 Chat Patches 的消息管线）
- `gui/ChatStudyYaclScreen.java` — 设置界面
- `assets/chatpatches/languages.json` — 语言注册表（85 种语言 / 124 个 locale 映射）
- 17 个语言文件的多语言支持

### 修改

- `ChatPatches.java` — 初始化钩子
- `ChatUtil.java` — 译文行渲染（支持折行缩进）
- `TextUtil.java` — 译文行格式化
- `mixin/gui/ChatComponentMixin.java` — 消息管线接入

### 保持不变

Chat Patches 原有的全部功能（聊天记录、时间戳、搜索、右键菜单、边界线等）**完全保留**。

---

## 安装

### 必需的

1. [Fabric Loader](https://fabricmc.net/use/) **≥ 0.19.0**（Minecraft **1.21.11**）
2. [Fabric API](https://modrinth.com/mod/fabric-api)（提供 lifecycle / networking / screen 三个子模块）
3. [Yet Another Config Lib (YACL)](https://modrinth.com/mod/yacl) v3

**就这三样，没有别的。**

### 安装

1. 把 jar 放进 `.minecraft/mods/`
2. 启动游戏
3. 设置界面是本模组**内置**的，不需要 Mod Menu 等任何额外 mod

### 配置翻译

配置文件在 `config/chatpatches/`：

| 文件 | 内容 |
|---|---|
| `1-translate.json` | 翻译设置 + 服务商列表 + API Key |
| `2-readaloud.json` | 朗读设置 + TTS 服务商 |
| `3-display.json` | 显示设置 |
| `4-glossary.json` | 术语表 / 不翻译表 / 缩写展开表 |
| `5-record.json` | 聊天记录设置 |

**改完要重启游戏才生效。**

---

## 构建

需要 **JDK 25**。

```bash
./gradlew :1.21.11-fabric:build
```

产物在 `versions/1.21.11-fabric/build/libs/`。

---

## 许可证

本项目继承上游 **LGPL-3.0**（见 [LICENSE](LICENSE)）。

这意味着：

- 可以自由使用、修改、分发
- 可以用于商业用途
- **修改后的版本必须以 LGPL-3.0 分发**
- **必须提供源代码**
- 必须保留原作者版权声明

---

## 借用与致谢

详见 [LICENSES/THIRD-PARTY.md](LICENSES/THIRD-PARTY.md)。

| 项目 | 用途 | 许可证 |
|---|---|---|
| [Chat Patches](https://github.com/mrbuilder1961/ChatPatches) | 基础模组（fork 源） | LGPL-3.0 |
| [franc](https://github.com/wooorm/franc) | 语言代码体系 / 文字系统分类 | MIT |
| [Roughly Enough Items](https://github.com/shedaniel/RoughlyEnoughItems) | 设置齿轮图标素材 | MIT |

### 特别感谢

- **mrbuilder1961** — Chat Patches 作者，本项目的基础
- **Titus Wormer** — franc 作者
- **shedaniel** — REI 作者，图标素材来源
- Fabric / YACL / Mod Menu 团队

---

## 已知限制

- 只支持 **Minecraft 1.21.11 + Fabric**
- 翻译功能需要网络和 API Key（或用免费的 B站 Index-Translate）
- 部分冷门语言（威尼斯语、低地德语等）只有 LLM 能翻，MyMemory 不支持
- 泰米尔语、波兰语暂未提供 UI 翻译

---

## 反馈

欢迎提 Issue。翻译质量问题请附上：原文、实际译文、期望译文、使用的服务商。
