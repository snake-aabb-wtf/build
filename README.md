# RikkaHub Sol · 个人构建中心

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的个人 Android 构建。除了上游的多 LLM 聊天，额外做了两块自用功能：**会话归档 + 回收站**、**会话分支对比**。

APK 由 GitHub Actions 构建并挂在 Releases，直接下载安装即可。

> A personal Android build of RikkaHub with conversation archive/trash and branch comparison. APKs are built by GitHub Actions and published to Releases.

---

## 下载安装

最新版本：[**Releases**](https://github.com/snake-aabb-wtf/build/releases/latest)

| 包 | 适用 |
|---|---|
| `app-github-arm64-v8a-debug.apk` | 绝大多数手机（推荐） |
| `app-github-universal-debug.apk` | 需要 x86_64 或不确定架构时 |

- 包名 `me.rerere.rikkahub.sol.debug`，与官方版**可以共存**
- 使用 Android 调试签名，可直接覆盖安装本仓库的旧版本，聊天记录与设置保持不变
- 首次安装需允许「安装未知来源应用」

---

## 自带功能

### 会话归档 + 回收站

- 删除会话先进回收站，可恢复，也可彻底删除
- 归档按助手分区，归档后的会话仍可继续聊，不会因为发消息自动解除归档
- 回收站里的会话不会出现在搜索、列表或普通打开路径里

### 会话分支对比

会话列表长按进入多选，选 **2–8 个**会话，点「对比分支」。

- **逐字比较**：角色与可见内容完全相同才合并成一个节点，不做相似度匹配
- 附件按文件内容 SHA-256 比较，不按路径；工具输出里嵌套的附件同样处理
- 三套视图共用同一份树：**缩进树**（默认，全文可读）、**自由画布**（双指缩放/拖动）、**泳道时间线**（按时间横向铺开）
- 点任意节点跳回聊天里的那条消息，返回对比页时位置保留
- 分叉节点有描边与「分叉」标记；会话结束处有终点标记，点开可看是哪几条会话
- 大图只渲染视口内节点；超长直线对话在画布/时间线里压成「… N 条」，点一下展开

---

## 版本

- tag 形如 `rikkahub-sol-3.1.1-b196`，`b` 后面是构建号，等于 `versionCode`
- 每个 Release 附 APK 大小与 SHA-256
- 构建流水线：`.github/workflows/rikkahub-apk.yml`，产物为 `assembleGithubDebug`

---

## 仓库结构

```
.github/workflows/     构建流水线（GitHub 只读这一层）
.github/ci/            CI 用的占位配置
rikkahub/              项目源码（git subtree，保留上游完整历史）
```

仓库是「个人构建中心」：每个项目在根下独占一个目录，新增项目不需要改动已有流水线。

---

## 从源码构建

本地：

```bash
cd rikkahub
./gradlew :app:assembleGithubDebug
```

`gradle.properties` 里 `org.gradle.jvmargs=-Xmx8g`，小内存机器需要先调低。

`app/build.gradle.kts` 同时应用了 `google-services` 与 `firebase-crashlytics`，缺少 `google-services.json` 会在配置阶段失败。CI 用 `.github/ci/google-services.json` 占位，仅让编译通过，不含真实 Firebase 凭据。

CI 环境：JDK 21 (temurin)、Android SDK（AGP 自动拉取 platform 37）、Node 22、pnpm 11。

---

## 同步上游

`rikkahub/` 以 `git subtree` 引入：

```bash
git subtree pull --prefix=rikkahub https://github.com/rikkahub/rikkahub.git master --squash
```

---

## 已知限制

- 使用调试签名，不适合正式分发
- 真机交互只做过代码层验证，画布捏合、chip 点击等手感仍以实际使用为准
- 超过 32MB 的本地附件首次对比会明显变慢（为了准确，没有做大小降级）
- 极深对话（数千层）在缩进树里可能触及 Compose 的尺寸上限
- 时间线左侧会话标题列不消费触摸，向左平移后点击该列可能命中被遮住的卡片
- 上下文压缩点被编辑后，指纹缓存可能命中旧值，需手动「重新构建对比」

---

## 许可

`rikkahub/` 继承上游 **AGPL-3.0**。分发修改版 APK 时须一并提供对应源码——本仓库即为对应源码。
