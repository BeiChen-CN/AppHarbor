# Trove 品牌

## 命名

- **Trove**：英文中“珍藏、宝库”之意，引申为把多个应用源的应用收进同一个地方。
- 以 T 作为核心识别符号；包名 `com.app.market`、已保存数据路径和 Git 仓库地址保持不变，避免升级与数据迁移成本。

## 图形

- 珊瑚色底 + 米白圆角瓦片，瓦片内部用负空间挖出 **T** 形。
- T 对应 Trove 首字母，同时保留“单块应用瓦片”的意象；圆角处理让它在 48px 下仍然清楚。
- 单色层使用独立的实心 T，而不是带孔的瓦片，保证 Android 13+ 主题图标在小尺寸下不糊。
- 颜色：珊瑚 `#FF6B4A`、米白 `#F7E9D7`、深林 `#12241E`。

## 资源

- `icon.svg` / `icon.png`：1024px 彩色图标，同时用于 Desktop 窗口图标。
- `icon-monochrome.svg` / `icon-monochrome.png`：单色层，支持 Android 13+ 主题图标。
- `preview.png`：默认、圆形遮罩、单色层、小尺寸与色板预览。
- Android 8–12 使用 `mipmap-anydpi-v26/ic_launcher.xml` 自适应图标；Android 13+ 使用 `mipmap-anydpi-v33/ic_launcher.xml` 的 monochrome 层。
