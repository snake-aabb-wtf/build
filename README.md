# build

个人构建中心。放源码树和 GitHub Actions 工作流，用 GitHub 的 runner 编译那些本地机器跑不动的项目。

## 结构

```
.github/workflows/     所有构建流水线（GitHub 只读这一层）
.github/ci/            CI 用的占位配置或辅助文件
<项目名>/              各项目的完整源码，子目录形式并列
```

每个项目在仓库根下独占一个目录，互不影响。新增项目就往里加一个目录，不需要改动已有流水线。

## 当前项目

### rikkahub

RikkaHub —— 支持多 LLM 提供商的 Android 客户端。
上游：https://github.com/rikkahub/rikkahub （AGPL-3.0）

- 源码：`rikkahub/`
- 工作流：`.github/workflows/rikkahub-apk.yml`
- 产物：`assembleGithubDebug`，APK 存为 artifact，保留 30 天
- 变体说明：debug 包使用 Android 调试签名，包名带 `.debug` 后缀，可与官方版共存

构建环境：JDK 21 (temurin)、Android SDK（AGP 自动拉取 platform 37）、Node 22、pnpm 11。

`app/build.gradle.kts` 同时应用了 `google-services` 和 `firebase-crashlytics`，缺 `google-services.json` 会在配置阶段失败。CI 使用 `.github/ci/google-services.json` 占位，仅让编译通过，不含真实 Firebase 凭据。

## 本地构建

```bash
cd rikkahub
./gradlew :app:assembleGithubDebug
```

注意 `gradle.properties` 里 `org.gradle.jvmargs=-Xmx8g`，小内存机器上需要先调低。

## 同步上游

rikkahub 以 `git subtree` 方式引入，保留完整上游历史：

```bash
git subtree pull --prefix=rikkahub https://github.com/rikkahub/rikkahub.git master --squash
```

## 许可

各子目录继承其上游项目的许可证。`rikkahub/` 为 AGPL-3.0，分发修改版需一并提供源码。
