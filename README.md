#前言:
 感谢腾讯code buddy 本人基础薄弱借用腾讯code buddy完成此项目 实际上0基础也可以,只要根据用户的视角描述后端需要呈现的东西codebuddy就可以根据描述完成项目,并且能够做到在第一次试运行就实现主要功能后续只需要围绕使用体验修改即可v1即可达成主要功能(没接广,但是如果腾讯大大看到发点工资也是可以的嘿嘿嘿) 觉得好用可以留下一个star吗谢谢XXX 

 # REDLAND答题辅助 (AnswerSearcher)
Android 悬浮窗题目搜索应用 —— 截屏识别题目，在**用户自选题库**中本地模糊匹配答案。

> 完全本地、离线可用：题库由用户在 App 内自行选择，不内置任何数据，也不需要任何 API Key。

## 功能特性

1. **自选题库** —— 在 App 内点击「选择题库」，从本机选择 `.xlsx` / `.csv` 文件，并通过弹窗指定「题目列 / 答案列 / 是否含表头」，选择结果会被记住（重启后自动恢复）。
2. **悬浮窗** —— 可拖拽、可点击穿透，截屏识别与答案展示都在悬浮窗内完成。
3. **区域识别（已优化）** —— OCR 只识别屏幕**中间一半**区域（竖向四等分取中间两份），避开题干之外的干扰文字，显著降低误匹配。
4. **本地模糊匹配** —— 基于最长公共子序列（LCS）+ 字符集重叠的评分排序，在题库中找最合适的答案，全部在设备内存中完成，低延迟。
5. **多种表格格式** —— 支持现代 Excel（`.xlsx`，不依赖第三方库，自研轻量解析）、CSV（含引号转义）。不支持旧版 `.xls`（请用 Excel 另存为 `.xlsx` 或 `.csv`）。
题库请自行寻找 up这里不提供
## 项目结构

```
AnswerSearcher/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/            # Gradle Wrapper（含 gradle-wrapper.jar）
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/answersearcher/app/
│       │   ├── AnswerApplication.kt      # 全局状态（题库数据）
│       │   ├── MainActivity.kt           # 选择题库 + 权限请求 + 列映射弹窗
│       │   ├── FloatingWindowService.kt   # 悬浮窗服务（核心：截屏/OCR/匹配）
│       │   ├── ScreenCaptureManager.kt    # MediaProjection 截屏
│       │   ├── OCRManager.kt             # ML Kit 中文 OCR
│       │   ├── ExcelManager.kt           # XLSX/CSV 读取
│       │   ├── XlsxReader.kt             # 自研轻量 .xlsx 解析（XmlPullParser）
│       │   ├── SearchEngine.kt           # LCS 模糊搜索
│       │   ├── BankPrefs.kt              # 题库选择与列映射的本地持久化
│       │   └── model/ExcelData.kt        # 数据模型
│       └── res/                          # 布局 / 图标 / 字符串 / 主题
```

## 构建

### 方式一：Android Studio（推荐）

1. 打开 Android Studio → File → Open → 选择本仓库的 `AnswerSearcher` 文件夹
2. 等待 Gradle 同步（会自动下载依赖）
3. 点击 Run 编译安装，或 Build → Build Bundle(s) / APK(s) → Build APK(s)

### 方式二：命令行

需要本地已安装 **Gradle 8.2**（或 JDK 17）。在本仓库根目录执行：

```bash
# 若没有 gradlew，可先生成 wrapper：
gradle wrapper --gradle-version 8.2

# 编译 Debug APK：
./gradlew assembleDebug
# 输出位于 app/build/outputs/apk/debug/app-debug.apk
```

> 首次构建需联网下载 Gradle 发行版与依赖。国内网络可在 `settings.gradle.kts` 中已配置的阿里云镜像加速。

## 使用流程

1. 打开应用 → 点击「选择题库」→ 从本机选择一个 `.xlsx` / `.csv` 题库文件
2. 在弹出的「列映射」对话框中指定：题目所在列、答案所在列，以及是否包含表头 → 确认
3. 点击「开启悬浮窗」→ 授予「悬浮窗权限」和「截屏权限」
4. 在任意应用（如考试/练习界面）中，点击悬浮窗的截屏按钮
5. 自动截取屏幕**中间区域** → OCR 识别中文 → 在题库中模糊匹配 → 显示答案
6. 点击关闭按钮退出悬浮窗

## 题库格式

以 `.csv` 或 `.xlsx` 的第一列为题目、第二列为答案为例（勾选「含表头」后首行被跳过）：

| 题目列 | 答案列 |
|---|---|
| 中国的首都是哪里 | 北京 |
| 地球上最大的洋是 | 太平洋 |
| … | … |

匹配基于题干文字与题库题面的字符重叠度，因此题库题面表述越接近屏幕识别到的文字，命中越准确。

## 技术要点

| 功能 | 技术方案 |
|---|---|
| 悬浮窗 | `WindowManager` + `TYPE_APPLICATION_OVERLAY` |
| 点击穿透 | 双窗口：Display（`FLAG_NOT_TOUCHABLE`）+ Control（可交互） |
| 截屏 | MediaProjection API |
| OCR | ML Kit Text Recognition（中文，on-device） |
| XLSX 解析 | 自研轻量解析：`ZipInputStream` + `XmlPullParser`（无第三方依赖） |
| CSV 解析 | 自带引号/转义处理 |
| 模糊搜索 | LCS + 字符集重叠评分排序 |
| 异步 | Kotlin Coroutines |

## 下载

不想自己编译？直接到 **[Releases](https://github.com/cypher-zero/AnswerSearcher/releases)** 下载最新 APK（`AnswerSearcher-LV1.2-release.apk`），安装即可使用（Android 7.0+）。若设备上已装过其他签名的旧版，请先卸载再安装本版。

## 最低要求

- Android 7.0（API 24）及以上
- 编译：Android Studio Hedgehog+，JDK 17，Gradle 8.2

## 隐私说明

- 不内置题库、不上传任何数据、无需联网、无需任何 API Key。
- 用户选择的题库文件仅在本机读取，并通过 Android 的持久化 URI 权限在重启后继续可用。

## License

[MIT](LICENSE)
