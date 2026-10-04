# BluBox

BluBox 是基于 [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) 修改的 Android 代理客户端，内核已从原版 sing-box 1.12 移植升级为官方 **sing-box 1.14.2**。界面、节点管理方式与订阅使用习惯与 NekoBox 保持一致。

- 包名：moe.nb4a（与 NekoBox 相同，可与现有定制版同签名覆盖更新）
- 当前版本：1.14.2（版本号跟随内核版本）
- 许可证：GPL-3.0（见 LICENSE，保留原作者版权声明）

## 与官方版的区别

- 应用名改为 BluBox，图标改为蓝色小人
- 内核换为官方 sing-box 1.14.2（原版为 1.12.19-neko-1），配置在生成出口自动迁移到 1.14 新格式
- 移除侧边栏推广入口、关于页捐款入口、Telegram 更新频道入口、分组设置「更新时移除重复」
- 关于页更新检查指向本仓库 Releases（正式版查稳定版，预览版查预发布版）
- 节点与订阅零内置，首次打开与官方初始状态一致，需自行导入

## 构建方法

工具链：

- JDK 17
- Android SDK + NDK 25.0.8775105，compileSdk 35
- Go 1.25.6，gomobile-matsuri / gobind-matsuri
- Gradle 8.10.2（wrapper 自带）

步骤：

```bash
./run lib core   # 构建 libcore.aar（sing-box 1.14.2 + libneko，按 buildScript/lib/core/get_source_env.sh 中固定提交拉取）
./run assemble   # 构建 APK，具体任务名以 run 脚本为准
```

注意：

- 构建机器 /tmp 空间不足时需把 TMPDIR、GOTMPDIR 指到大容量目录，否则 Go 链接阶段会失败
- 发布签名需自备 keystore，在 gradle.properties / 构建脚本中配置自己的签名信息，本仓库不包含任何签名文件与密码
- sing-box 源码为本仓库 sing-box 分支（neko-1.14.2），对应官方 v1.14.2 加 neko 补丁，提交记录见仓库内 sing-box 目录说明

## 源码对应关系

- App：NekoBoxForAndroid 1.4.2 (230) 基线 + 本仓库提交
- 内核：sing-box v1.14.2 + Matsuri neko 补丁 + 本仓库适配提交（1.14 平台接口移植、boxapi RoutedFlow）
- libneko：按 get_source_env.sh 固定提交引用

## 安装说明

- 本应用签名与官方 NekoBox 不同，首次从官方版本切换过来需先卸载官方版（请先导出自己的订阅/配置再导入）
- 同一签名（本仓库 Releases）的后续版本可直接覆盖更新，数据不丢失

## 致谢

基于 MatsuriDayo/NekoBoxForAndroid 与 SagerNet/sing-box 开发，遵循 GPL-3.0 开源。

## 仓库结构

- 仓库根目录 = App 工程（基于 NekoBoxForAndroid 1.4.2）
- `vendor/sing-box/` = sing-box 1.14.2 内核源码（分支 neko-1.14.2，提交 fcfd6097），构建 libcore 前请将其放到 App 工程同级目录并命名为 `sing-box`（或建软链接），`buildScript/lib/core/get_source_env.sh` 中的固定提交已与此对应
- libneko 按 get_source_env.sh 中 COMMIT_LIBNEKO=1c47a3a 由构建脚本拉取
