# OMY 工具箱集成与 31433 发版验证

基线：官方 31432（`293dba5c`）。本文件记录 31433 相对它的集成内容与验证口径。

## 集成内容

- 内置 TEE 引擎切换为 Oh My Keymint 1.2.6（`third_party/OhMyKeymint/`）；注入判定按 OMY 的 PID 作用域载荷（`/data/adb/omk/injector.payload` + keystore2 maps），且要求**每一个** keystore2 实例都被注入。
- 保留 31432 的启动修复：post-fs-data 准备阶段、分区/槽位重探、root 就绪后重算首页状态、外部 KernelSU 文件识别。
- 工具箱（`ToolsScreen`）：TEE 诊断与软件修复、厂商 RKP 回写（二次确认）、密钥池、SUSFS 内核版本编辑、受限本地 OMY WebUI。
- 密钥池：两路固定 HTTPS 源、最多十个不同身份候选、本地 XML/证书窗口/链签名/私钥挑战校验；应用时先暂停 keybox 自动替换，导入失败恢复原设置。
- 自定义背景整页可见：浅/深档壁纸非空时导航宿主与根层不再铺实色；31432 的独立全局背景（透明度/可读性遮罩）优先级不变，二者互不覆盖。
- 首页越狱模式（late-load）优先显示。

## root 授权修复（对应记忆 `external-ksu-root-regression`）

- `createRootShell` 候选恢复为：先内置 `debug su`，再 PATH `su`（全局命名空间 `su -mm`）；以显式收集的 `id -u` 输出等于 `0` 判定成功。
- 删除“`su -v` 必须含 MAGISK”的品牌门槛——它会把外部 KernelSU/Next/APatch 已授权的 `uid=0` shell 主动丢弃。
- 不按安装路线排他选择候选：`RootRoute` 只描述安装/UI 语义，不再决定能否取 root。
- 自身驱动身份仍只认 `anon_inode:[xecpro_driver]`；外部 root 不冒充 XEC 管理者身份（`Natives.version == 0` / `isManager == false` 时为外部 root）。
- 缓存 shell 记录自身驱动能力快照：晚加载或加冕后触发一次后台提升，不丢弃在用的外部 root；`probeOnce` 返回 `null`（并发占用/能力快照过期）不计失败。

## 验证

- `cargo ndk -t arm64-v8a check` / `clippy` / `cargo fmt --all`：通过（仅既有未使用依赖告警）。
- `cargo ndk ... build --release` 后 `clean assembleRelease`：通过（含 C++ 与 Kotlin）。
- 本地包核对：versionCode 31433、`assets/omy/index.html` 在包内、`libxeccli.so` 与 Gradle 剥离产物字节一致。
- 正式 CI（run 43933143163）全绿：正式证书 `fa5d8124…`、v2 签名、zipalign 通过、同次构建的 `xudc` 经 `llvm-strip --strip-all` 与包内 `libxeccli.so` 完全一致。
- 发布：`v31433-a0bc82b8`，APK SHA256 `c291de9b0fff8fe0af3cd09484a6ed86461a7d0a1972e7d74e5c2bd928015806`。

## 未验证

- 未做真机验证：外部 KernelSU 授权（GKI/非 GKI）、Magisk 设备、OMY 引擎实际注入、密钥池导入与重启后提供者恢复。
- 密钥池两路上游在本机取数超时，未声明当前可用候选数量。
- 证书在有效期内、私钥匹配、链签名自洽，不等于未被吊销或被证明服务接受。
