# Android 双形态应用开发参考方案（电视盒子端 + 手机端）

> 参考项目：**FongMi/TV（蜂蜜版）** · **takagen99/Box（TK 版）**
> 整理日期：2026-09-24 ｜ 结论先行：**推荐以 FongMi/TV 的 flavor 架构为骨架、Box 的兼容性策略为补充**，单一代码库产出电视/手机双形态 APK。

---

## 1. 两个参考项目对比

| 维度 | FongMi/TV（蜂蜜版） | takagen99/Box（TK 版） |
|---|---|---|
| 定位 | 架构最现代的分支，leanback/mobile 双产物 | 电视盒子端经典分支（TVBox OSC 系），遥控器体验打磨最深 |
| 构建体系 | JDK 21 + 新版 AGP，Gradle Version Catalog（libs.versions.toml） | 传统 Groovy DSL，AGP 保守（曾因 proguard 问题回退 8.2 升级） |
| 双端处理 | Gradle flavor：`app/src/main` 共用 + `leanback` / `mobile` 各自 UI | 以电视端为主体，手机端为辅（代码中可见双击快进等移动端适配） |
| 播放内核 | Media3/ExoPlayer 与 mpv，软硬解无缝切换；内核为预编译 AAR（`app/libs/lib-*.aar`，不入 Git） | 系统 / IJK / Exo 三内核运行时切换（`HawkConfig.PLAY_TYPE: 0=系统, 1=IJK, 2=Exo`），IJK 另有软硬解开关 |
| 源扩展 | CatVod Spider 接口：Java JAR / Python（chaquopy）/ JS（quickjs）三语言共用一套 Result JSON 契约 | 同源系 Spider 机制（pyramid / quickjs / xwalk 模块） |
| 特色功能 | 直播（M3U/TXT/JSON + XMLTV EPG）、弹幕、字幕/音轨/倍速、片头片尾跳过、画中画、DLNA 投放、Android Auto、本地 HTTP API、设备同步 | 弹幕、遥控快进快退优化、返回键取消加载、"再按一次退出"、DOH、豆瓣/推荐/历史首页 |
| minSdk | Android 7.0（API 24） | OSC 系历史悠久，兼容更老设备 |
| 文档 | 官方文档站 fongmi.github.io/TV（features/config/spider/local） | README + 源码注释为主 |

## 2. FongMi/TV 架构要点（骨架来源）

### 2.1 flavor 双产物结构

```
app/
├── src/main/        # 共用层：业务逻辑、数据、网络、播放内核封装、工具
├── src/leanback/    # 电视端：遥控器 UI、焦点导航、10-foot 布局
└── src/mobile/      # 手机端：触屏 UI、手势、Material 布局
```

- 一个代码库、一套业务逻辑，两条构建命令：
  - `:app:assembleLeanbackRelease` → 电视版
  - `:app:assembleMobileRelease` → 手机版
- Release 按 ABI 分包（arm64-v8a / armeabi-v7a），输出至 `Release/apk/`
- Gradle 模块：`:app`（壳 + UI）、`:catvod`（源解析/Spider 接口）、`:chaquo`（Python 承载）、`:quickjs`（JS 引擎）；仓库另有 forcetech / hook / jianpian / thunder / tvbus / zlive 等协议组件目录

### 2.2 值得借鉴的四个设计

1. **UI 与业务彻底分离**：main 层不感知设备形态，新增形态（如车机）零业务改动。
2. **播放器统一抽象**：上层只面对一致的播放控制接口，底层 Exo/mpv 可切换，软硬解互为兜底。
3. **扩展协议标准化**：Spider 三语言共用一份 Result JSON 契约——"爬虫取数，App 呈现"，数据层与呈现层解耦。
4. **文档随代码维护**：功能、配置字典、扩展接口、本地 API 四大文档板块。

## 3. Box 的补充借鉴点

- **运行时播放内核切换 + IJK 软硬解开关**：低配盒子兼容性的典型兜底策略（一个源 Exo 解不了就切 IJK 软解）。
- **遥控器交互细节**：快进快退步长、返回键取消加载、"再按一次退出"、焦点记忆——这些是电视端口碑细节。
- **默认参数集中管理**（`App.java initParams`）：初始化策略一处可查。
- **教训**：工具链升级（AGP 8.2）需配套验证 proguard 规则，否则 release 构建出问题只能回退。

## 4. 电视盒子端 vs 手机端核心差异（需求实现必须逐条对照）

| 维度 | 电视盒子端 | 手机端 |
|---|---|---|
| 输入方式 | D-pad 遥控器：焦点移动 + OK/菜单/返回 | 触摸：点击、长按、滑动手势（亮度/音量/进度） |
| UI 形态 | 10-foot UI：大字号、大卡片、横向焦点行、留白多 | 密集列表/网格、Tab、抽屉导航 |
| 焦点管理 | 焦点路径、高亮、防焦点丢失、记忆上次位置 | 无焦点概念，直接命中 |
| 返回/退出 | 逐级回退 + 二次确认退出 | 手势返回、系统返回键 |
| 性能/内存 | 硬件偏弱（1-2G RAM、老 SoC），需软解兜底、缓存克制 | 机型强，可预加载、高帧率 |
| ABI | 大量 armeabi-v7a 老盒子仍在服役 | arm64-v8a 为主 |
| 生命周期 | 长期待机、息屏/HDMI CEC、屏保策略 | 旋转、分屏、后台回收 |
| 播放辅助 | 画中画、背景音频、DLNA 投放/接收 | 同左 + 系统分享、悬浮窗 |
| 网络 | 有线为主、DOH 抗 DNS 污染、超时重试更宽容 | WiFi/蜂窝切换频繁，需网络状态监听 |

## 5. 我们项目的推荐工程结构（如从零自研）

```
project/
├── app/
│   ├── src/main/           # 共用：业务逻辑、数据层、仓库、DI、通用组件
│   ├── src/leanback/       # 电视端 UI（Application、Activity、焦点组件）
│   └── src/mobile/         # 手机端 UI（Application、Activity、触屏组件）
├── player/                 # 播放抽象模块：IPlayer 统一接口
│   ├── player-exo/         #   Media3/ExoPlayer 实现
│   ├── player-ijk/         #   IJKPlayer 实现（软/硬解开关）
│   └── player-mpv/         #   mpv 实现（可选）
├── core-network/           # 网络、DOH、重试策略
├── core-source/            # 配置源解析（对标 catvod，Spider 契约）
└── core-common/            # 工具、日志、埋点
```

**五条实现原则**

1. 业务逻辑只写一份，放 `main`；形态差异只允许出现在 flavor 目录。
2. 播放器对上层只暴露 `IPlayer` 统一接口（prepare/start/pause/seek/setSpeed/track…），内核切换做成运行时设置而非编译期。
3. 交互事件抽象：遥控器按键与触屏手势在各自 flavor 层映射为**同一组控制指令**，再下发到共用播放控制层。
4. 双端同版本号发布，APK 按 ABI 分包；电视版 launcher 走 LEANBACK_LAUNCHER，手机版走普通 LAUNCHER。
5. UI 资源（尺寸/字号/间距）按 flavor 各自维护，颜色/主题色等品牌资源放 main 共用。

## 6. 双端兼容验收清单（每个版本必过）

- [ ] 电视端：遥控器全程可用（焦点不丢失、可循环）、OK/返回/菜单语义正确
- [ ] 电视端：二次确认退出、息屏恢复、HDMI 插拔后播放状态正确
- [ ] 手机端：手势进度/音量/亮度、旋转重建、分屏不崩溃
- [ ] 低配盒子（1G RAM，armeabi-v7a）：软解可播、不 OOM
- [ ] 双内核切换：Exo 失败自动/手动切 IJK 路径可用
- [ ] 弱网与 DNS 异常场景重试策略生效
- [ ] Android 7.0 ~ 最新系统遍历冒烟

## 7. 合规提示

此类聚合播放器生态常被用于播放未经授权的内容。两个参考项目均明确声明"App 本身不内置任何内容来源"。我们开发时同样必须：**不内置盗版源、内容来源合法、用户自行配置接入**，避免法律风险。

## 8. 待确认事项（进入实际开发前）

1. **开发方向**：从零自研（参考此架构）？Fork FongMi/TV 或 Box 二次开发？还是只做某个具体功能模块？
2. **形态优先级**：电视盒子优先还是手机优先？最低 Android 版本要求？
3. **是否需要配置源/Spider 扩展机制**？（涉及合规边界，需先定内容来源策略）
