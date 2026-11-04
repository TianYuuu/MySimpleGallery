# MySimpleGallery

本地图片浏览应用，基于 [Simple Gallery](https://github.com/SimpleMobileTools/Simple-Gallery) 6.28.1（foss 版）改造。
包名 `com.simplemobiletools.gallery.pro.mod`、独立签名，可与官方图库共存安装，数据互不影响。

## 相对原版新增的功能

### 图库首级列表

- **按所属文件夹分组（仍平铺在首级）**：设置 →「更改视图类型」里的第三个状态。所有相册仍直接显示在第一级，
  但每组前面插一整行分区标题（文件夹名 + 相册数），不同父文件夹的相册绝不会混在同一行。
  与原版「直接分组文件夹」（逐级点进去）互斥，三个状态任选其一。
- **记住浏览位置**：进入相册直接回到上次停的位置，重启应用也有效。按文件路径记录而不是序号，
  照片增删后位置依然对得上；随机排序与搜索状态下不记忆。
- **回到顶部**：下滑后顶部出现一个半透明药丸，点击回第一屏并清除已记录的位置。

### 全屏查看

- **缩略图预览**：大图解码期间先铺一张小图（`override(屏宽/3)`），不再白屏。
- **底部缩略图条**：3:4 竖格，边滑边翻（手指滑到哪张，大图立刻切到哪张，不等停手）；
  长按可上下拖动整条位置并持久化；上滑打开全屏缩略图网格。
- **全屏缩略图网格**：BottomSheet 占 92% 屏高，4 列带页码，进度条可连续跳页，
  可在「统一裁切 / 按原图比例」间切换。
- **点击分区**：L 型 / Kindle 型 / 边缘 / 左右 / 关闭，另加水平·垂直·双向镜像。
  生效时会隐藏原版左右两侧的点击条，避免与分区结论冲突。
- **三段式点击**：点一下收起顶部信息（保留缩略图条）→ 再点只剩图片 → 第三下全部恢复。
- **页码**：固定在图片底部，40% 透明蓝色纯数字，不跟缩略图条移动。

## 编译

需要 JDK 17 与 Android SDK（compileSdk 34 / build-tools 35）：

```bash
./gradlew :app:assembleFossDebug
# 产物在 app/build/outputs/apk/foss/debug/
```

签名：把 `keystore.properties_sample` 复制为 `keystore.properties` 并填自己的 keystore
（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）。这两个文件都在 `.gitignore` 里，不会进仓库。

### 为能编译通过做的三处修复

原仓库的构建链已失效，以下三处是让它能编起来的必要改动，都已在仓库内：

1. **新增 `local-maven/`**：`Simple-Commons`、`com.google.vr:sdk-panowidget`、`info.androidhive:imagefilters`
   等依赖所在的 jcenter 已下线、jitpack 对老提交构建失败，故以本地 maven 仓库的形式提供。
   （`com.github.duolingo:rtl-viewpager` 是个未被使用的传递依赖，用等价的本地桩顶替。）
2. **`settings.gradle.kts`**：首位加 `local-maven`，移除失效的 `jcenter()`。
3. **AGP 7.4.0 → 8.1.4**：旧版内置的 R8 在 dex 阶段处理 `androidx.viewpager` 的 Kotlin 元数据会直接崩。

## 版本号

`gradle/libs.versions.toml` 里的 `app-version-versionName` / `app-version-versionCode`，
每版递增末位（6.28.3、6.28.4…），versionCode 同步 +1。

## 许可证与致谢

与原项目一致，GPLv3。界面与交互的大部分实现来自
[SimpleMobileTools/Simple-Gallery](https://github.com/SimpleMobileTools/Simple-Gallery)；
全屏浏览的若干交互（缩略图条 / 网格 / 点击分区）参考了
[MyPixEz](https://github.com/TianYuuu/MyPixEz) 的行为设计，代码为 Kotlin 重写。
