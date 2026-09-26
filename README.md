# mini辅助 (mini-assist)

Mindustry **v160** 的客户端 mod，两个小工具合在一起，设置页在「设置 → mini辅助」。

## 功能

**钻机产出** —— 按住按键（默认 `F`）并移动鼠标张出选区框，选区左上角按矿物分行显示框内钻机的
**净输出**与**实际产出**（每秒多少）。纯客户端只读，联机可用，服务器不需要装。
覆盖三类钻机：连续钻机（`mechanical-drill` 那一系）、冲击钻（`impact-drill` / `eruption-drill`）、
激光钻（`plasma-bore` / `large-plasma-bore`）。

**内存块读写** —— 鼠标悬停内存块时，方块上方出现两个图标：**左边**是读（整块复制到剪贴板）、
**右边**是写（把剪贴板里的数字写回槽位），用鼠标左键点；也可用 `Ctrl+C` / `Ctrl+V`。仅单机可用。

## 设置

设置页里可改：选区按键、钻机产出开关、是否只统计己方钻机、净输出的两个计入口径、
选区边框与范围文字、单边最多统计多少格、实际产出是否取平均（及平均窗口）、内存块最大复制长度。

## 安装

把 `build/libs/mini-assist.jar` 放进游戏 `mods/` 目录并启用。

## 构建

需要 JDK 17+，项目自带 Gradle wrapper：

```powershell
$env:GRADLE_USER_HOME = 'D:\桌面\deepseek_home\构建工具\缓存\gradle-home'   # 复用已缓存的 jitpack 依赖
.\gradlew.bat jar --console=plain
```

产物：`build/libs/mini-assist.jar`

## API 契约校验

mod 依赖的游戏 / Arc 成员是 `compileOnly`，改名或改签名编译期发现不了，只会在游戏内以
`NoSuchFieldError` / `NoSuchMethodError` 爆出。`tools\verify.cmd` 会先构建，再用反射逐一
断言这些成员真实存在且签名匹配（等价于 JVM 链接期检查），退出码非 0 即不匹配。

```powershell
tools\verify.cmd
```

## 目录结构

```
mini-assist/
├── src/miniassist/              源码（MiniAssistMod 为入口，drill/ 与 memory/ 两个 feature）
├── bundles/                     中英文案（bundle.properties / bundle_zh_CN.properties）
├── tools/                       API 契约校验（verify.cmd → verify.ps1 → VerifyApi.java）
├── docs/设计说明.md              实现细节与取舍（原 README 全文，非必需）
├── mod.json  build.gradle  gradlew*  gradle/
└── LICENSE                      GPL-3.0
```

## 作者 / 许可

蓝色大肥鱼 · GPL-3.0
