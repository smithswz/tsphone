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
