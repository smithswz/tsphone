# TS Phone

TeamSpeak 3 安卓客户端，基于 [ts3j](https://github.com/Manevolent/ts3j)（完整 TS3 客户端协议，含语音）。

## 功能

- 多服务器书签（自定义域名走 TSDNS/SRV 解析，自动识别非默认端口）
- 频道树：展开/收起、说话者指示、每用户静音状态（闭麦/闭喇叭）实时同步
- 双向语音：RMS 语音激活（灵敏度可调）、Opus 编解码、多路混音
- 输出控制：一键静音（🔊）、外放/听筒选择（🎧）、麦克风开关（🎤）
- 文字聊天：频道聊天 + 私聊，Room 持久化（每会话 500 条上限，自动清理）
- Poke 提醒（系统通知）
- 断线横幅 + 手动重连；前台服务保活（锁屏不断语音）
- 中英双语（跟随系统）

## 构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

依赖的 ts3j 走 JitPack（首次构建需联网）。libopus（arm64-v8a / armeabi-v7a / x86_64）与 JNA 运行时已预编译进 `app/src/main/jniLibs/`，构建无需 NDK。

## 已知限制

- **测试服务器权限**：`niumagaoshua.ts3.red` 拒绝普通客户端的 `channellist`/`clientlist` 命令，且 `notifycliententerview` 不带频道字段（cfid=0）——app 通过订阅事件 + `clientinfo` 逐客户端查询恢复频道归属，新增频道/用户移动仍靠事件实时更新
- **无 AEC**：通话回声消除依赖设备硬件（`VOICE_COMMUNICATION` 之外用普通 MIC 输入），部分机型开麦可能互相回声
- **三星设备**：后台冻结（FreecessController）可能中断语音，依赖前台服务通知保活；日志信息级可能被过滤（排障用 W 级日志）
- 书签密码以明文存 Room（个人 v1 可接受）
- 仪器化测试（Room prune）因测试机 Magisk 环境异常暂未纳入常规流程，逻辑由 SQL + 手动验证覆盖

## 测试服务器

- 地址：`niumagaoshua.ts3.red`（默认端口 9987 填写即可，实际端口由 TSDNS SRV 记录指定：49.234.4.31:3946）
- 无密码；app 首次启动自动生成 TS3 身份（level 12）
