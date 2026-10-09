# AppHarbor 图标

采用用户选定的图标 1（蓝色港湾 A + 海浪），按参考轮廓制作成矢量资源。

- `icon-selected.png`：原始选定概念图，保留设计来源。
- `icon.svg` / `icon.png`：矢量实现及 1024px 彩色导出。
- `icon-monochrome.svg` / `icon-monochrome.png`：透明背景单色图标，保留海浪与 A 之间的空隙。
- `preview.html` / `preview.png`：从实际 Android / Compose 矢量资源渲染的预览；莫奈颜色是示例，实际由系统和桌面决定。

Android 8–12 使用彩色自适应图标；Android 13+ 通过 `drawable-v33/ic_launcher.xml` 的 `<monochrome>` 层支持壁纸取色主题图标。需使用支持主题图标的桌面并开启对应设置。前景和单色层使用相同几何轮廓，画布为 108dp，主图形位于安全区域。关于页和 Desktop 使用同一品牌轮廓。

应用显示名称与打包名称为 AppHarbor，包名 `com.app.market`、已保存数据路径以及 Git 仓库地址保持兼容。

参考：[Android 自适应与主题图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。
