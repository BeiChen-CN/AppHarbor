# Tessera（聚格）品牌

## 命名

- **Tessera**：马赛克中的单块镶嵌片。多块瓦片拼成完整图案，对应“多应用源汇入一个商店”。
- **聚格**：中文名，取“聚合 + 格子”之意，与三块应用瓦片和空置下载格的图形对应。
- 包名 `com.app.market`、已保存数据路径和 Git 仓库地址保持不变，避免升级与数据迁移成本。

## 图形

- 三块不规则圆角瓦片：代表不同应用源，整体旋转 -7° 让网格脱离死板对齐。
- 空置格中的向下箭头：代表下载入口，同时保留“还有一格可加入”的开放含义。
- 颜色：深林 `#12241E`、米白 `#F7E9D7`、珊瑚 `#FF6B4A`、琥珀 `#FFC857`。
- 不沿用旧品牌的字母 A、海浪、港湾或蓝色渐变。

## 资源

- `icon.svg` / `icon.png`：1024px 彩色图标，同时用于 Desktop 窗口图标。
- `icon-monochrome.svg` / `icon-monochrome.png`：单色层，支持 Android 13+ 主题图标。
- `preview.png`：默认、圆形遮罩、单色层、小尺寸与色板预览。
- Android 8–12 使用 `drawable-anydpi-v26/ic_launcher.xml` 自适应图标；Android 13+ 使用 `drawable-v33/ic_launcher.xml` 的 monochrome 层。
