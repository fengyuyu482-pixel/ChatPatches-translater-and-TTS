# 第三方素材声明（THIRD-PARTY NOTICES）

本项目（ChatStudy / Chat Patches fork）使用或借用了以下第三方资源。

---

## 1. Chat Patches（基础模组 · fork 源）

- 项目：Chat Patches
- 作者：mrbuilder1961 及贡献者
- 地址：https://github.com/mrbuilder1961/ChatPatches
- 许可证：GNU Lesser General Public License v3.0
- 说明：本项目是 Chat Patches 的 fork，**fork 已获得原作者许可**。
  原有功能与代码版权归原作者所有；本项目在此基础上的新增与修改同样以 LGPL-3.0 分发。

---

## 2. franc（语言代码体系与文字系统分类）

- 项目：franc — Natural language detection
- 作者：Titus Wormer 及贡献者
- 地址：https://github.com/wooorm/franc
- 许可证：MIT License

### 使用方式（重要）

本项目 **没有引入 franc 的代码或其数据文件**（`data.js` / `expressions.js`）。

实际使用范围仅为：

1. **语言代码命名体系** —— 采用 franc 使用的 ISO 639-3 三字母代码
   （`cmn`、`deu`、`jpn` 等）作为本项目语言注册表的主键。
2. **文字系统分类方式** —— 参考 franc 按 script（Latin / Cyrillic / Arabic /
   Devanagari / Han 等）对语言分组的方式，用于「定向翻译」的字形判定。

`assets/chatpatches/languages.json` 中的语言列表、显示名、locale 映射、
服务商能力矩阵等数据均为本项目自行整理（显示名取自 Minecraft 官方
`assets/*/lang/*.json` 的 `language.name` 字段，不是 franc 的数据）。

按 MIT 许可证要求，附上完整许可全文。

---

## 3. Roughly Enough Items (REI)（图标素材）

- 项目：Roughly Enough Items
- 作者：shedaniel 及贡献者
- 地址：https://github.com/shedaniel/RoughlyEnoughItems
- 许可证：MIT License

### 使用范围

设置界面的齿轮图标：

- `assets/chatpatches/textures/gui/settings_gear.png`
- `assets/chatpatches/textures/gui/settings_gear_hover.png`

来自 REI 的 `assets/roughlyenoughitems/textures/gui/recipecontainer.png`
左上角 14×14 区域，经裁切与缩放。

---

## MIT License 全文

以下许可适用于 franc 与 Roughly Enough Items：

```
MIT License

Copyright (c) 2014 Titus Wormer <tituswormer@gmail.com>
Copyright (c) 2008 Kent S Johnson
Copyright (c) 2006 Jacob R Rideout <kde@jacobrideout.net>
Copyright (c) 2004 Maciej Ceglowski

Copyright (c) shedaniel and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 运行时依赖（不打包，由用户自行安装）

| 项目 | 用途 | 许可证 |
|---|---|---|
| Fabric Loader | 模组加载器 | Apache-2.0 |
| Fabric API | 基础 API（lifecycle / networking / screen） | Apache-2.0 |
| Yet Another Config Lib (YACL) | 配置界面框架 | LGPL-3.0 |
| Minecraft | 游戏本体 | Mojang EULA |

以上依赖**不包含在本项目的 jar 内**，由用户自行安装。

配置文件界面是本模组自己实现的（基于 YACL 构建），**不需要 Mod Menu**。
