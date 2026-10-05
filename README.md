# BluBox

<h3 align="center">一款开源的安卓代理客户端</h3>

<p align="center">
  基于 NekoBox for Android，使用 sing-box 内核。
</p>

---

## 关于

BluBox 是一款开源的安卓代理客户端，基于
[NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid)
开发，使用 [sing-box](https://github.com/SagerNet/sing-box) 作为内核。

它在熟悉的安卓界面里提供 sing-box 的网络能力，可以在一个
地方管理节点配置、分组和分流规则。

BluBox **不内置任何代理服务器或订阅**，请导入和使用你自己的配置。

## 功能

- 安卓 VPN（TUN）模式
- 多种代理协议，以内置 sing-box 内核支持并在 App 中提供的为准
- 订阅和节点配置管理
- 代理分组和规则分流
- DNS 配置
- IPv4 和 IPv6 支持
- 配置导入和导出

实际功能会随内置 sing-box 版本和当前 App 版本有所不同。

## 下载

在 [GitHub Releases](https://github.com/BluBoxAndroid/BluBox/releases/latest)
下载最新版 APK。

请根据你的设备选择对应的安装包。

## 技术构成

- Android（Kotlin）
- [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [sing-box](https://github.com/SagerNet/sing-box)（Go）

## 开源说明

BluBox 是自由开源软件，遵循 GNU 通用公共许可证 v3.0 发布。

本项目是基于 NekoBox for Android 的修改作品。上游项目的
版权和许可证信息请查看其各自的项目页面。

## 免责声明

BluBox 是一款用于合法用途的网络工具。

用户应遵守所在地区和网络环境适用的法律法规。

## 致谢

感谢开源社区的工作，BluBox 的诞生离不开他们。

特别感谢：

- [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 及其贡献者
- [sing-box](https://github.com/SagerNet/sing-box) 及其贡献者
- [OwnBoxForAndroid](https://github.com/Own716/OwnBoxForAndroid)（部分功能设计参考）
- Android 开源项目
