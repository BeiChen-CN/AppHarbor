# Trove 宣传视频 · Remotion

45 秒中文竖屏视频，1080 × 1920，30 fps。蓝色港湾视觉、功能示意动效、原创电子配乐，无旁白。全部动画由 Remotion 按帧驱动，可逐帧编辑与重复渲染。

## 输出

- `out/Trove-promo-vertical.mp4`：H.264 / AAC，适合社交平台。
- `out/Trove-poster.png`：片头封面。
- `Trove.zh-CN.srt`：可选外置字幕；画面已经含有宣传文案。
- `qa/`：关键帧与画面检查资料，不纳入版本控制。

## 编辑与导出

要求 Node.js 22+、npm。当前依赖版本由 `package-lock.json` 固定。

```powershell
cd C:\AppMarket\promo
npm ci
npm run studio
npm run check
npm run render
npm run poster
```

打开 Remotion Studio 后选择 `TroveVertical`。主要文案、布局、时序位于 `src/Root.tsx`。本项目用 Windows 自带的 Microsoft YaHei 字体渲染；其他系统需安装适当的中文字体，否则版式与字形可能变化。

## 分镜

| 时间 | 内容 |
| --- | --- |
| 0–4.6 秒 | 品牌与钩子：找应用，少一点折腾 |
| 4.6–11.2 秒 | 19 个应用源，独立配置来源 |
| 11.2–18.8 秒 | 纯净搜索，推广 / 快应用 / 预约过滤 |
| 18.8–27.4 秒 | 下载补丁，本地合成，来源直连 |
| 27.4–35 秒 | 历史版本与安装方式 |
| 35–45 秒 | 手机与桌面，GitHub 项目入口 |

## 素材与事实依据

- 图标来自仓库 `branding/trove/icon.svg`，视频中复用该品牌资源。
- 所有界面、应用名称与下载进度都是功能示意，并在画面标注；不是运行截图。
- 来源数量经 `domain/.../model/market/AppSource.kt` 的 19 个枚举核对；独立设置搜索、更新与今日来源不意味着 19 个来源都支持每项功能，也不表示同时检索全部来源。
- 搜索过滤依赖来源能力。增量更新仅在来源提供兼容补丁时可用。
- 历史版本依赖支持来源；静默安装需 Shizuku 服务或 Root 权限。
- Desktop 的展示强调搜索与跨平台界面，已装应用管理与安装功能仅在 Android 可用。
- 片尾保留仓库 README 中的“仅供技术研究学习及交流”说明。

`public/trove-original.wav` 是 `scripts/make_music.py` 从振荡器、包络与确定性噪声生成的原创配乐，不包含外部采样。需要重新生成时，安装 Python 与 numpy 后运行 `npm run music`。音频预先生成并保存在仓库中，常规渲染无需 Python。

Remotion 官方文档：[渲染](https://www.remotion.dev/docs/cli/render)、[动画](https://www.remotion.dev/docs/spring)、[音频](https://www.remotion.dev/docs/html5-audio)。
