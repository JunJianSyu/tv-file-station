# TV 文件中转站（TvFileStation）

Android TV 上的局域网文件中转站：TV 开启服务后，电脑浏览器即可上传文件、管理目录，零客户端、零配置。

## 功能

- **Web 文件管理器**：电脑浏览器打开 `http://<TV的IP>:8080`，输入配对码后即可：
  - 浏览目录树、进入任意目录
  - 拖拽上传文件 / 整个文件夹（保留目录结构）
  - 新建目录、重命名、删除
  - 同名文件自动追加 `(n)` 后缀，不会误覆盖
- **配对码验证**：每次开启服务生成 6 位配对码，显示在电视屏幕上，防止局域网内其他人误连
- **手动开启**：App 内一键开关服务，关闭即断开
- **存储位置**：设备内部存储根目录（`/storage/emulated/0`）

## 技术实现

| 模块 | 方案 |
|---|---|
| HTTP 服务 | NanoHTTPD 2.3.1，端口 8080 |
| Web 前端 | 内嵌单页 HTML/JS（assets/web/index.html），拖拽 + `webkitdirectory` |
| 上传协议 | `PUT /api/upload?dir=&relpath=`，请求体为原始文件字节，XHR 带进度 |
| 鉴权 | 配对码登录 → 下发 HttpOnly Cookie 令牌，后续请求校验 |
| 路径安全 | 所有路径 canonical 化后校验不越出根目录，防路径穿越 |
| 文件权限 | `MANAGE_EXTERNAL_STORAGE`（所有文件访问），首次使用引导授权 |
| 保活 | 前台服务 + WakeLock + WifiLock，防 TV 休眠断连 |

## 构建

本机未安装 Android SDK/Gradle，请用 **Android Studio** 打开本项目目录：

1. Android Studio → Open → 选择 `tv-file-station` 目录
2. 等待 Gradle Sync 完成（首次会自动下载 Gradle Wrapper 与依赖）
3. Build → Build APK(s)
4. 产物位于 `app/build/outputs/apk/debug/app-debug.apk`

命令行构建（需已安装 Android SDK）：

```bash
cd tv-file-station
gradle wrapper --gradle-version 8.7   # 首次生成 wrapper
./gradlew assembleDebug
```

## 安装到 TV

1. 将 `app-debug.apk` 拷到 U 盘
2. U 盘插入电视/盒子，用文件管理器打开 APK 安装
3. 首次打开 App → 点「去授权」授予「所有文件访问」权限
4. 点「开启服务」，电视显示访问地址与配对码
5. 电脑浏览器打开地址，输入配对码，开始传文件

## 小米电视安装注意事项

小米电视有额外的政策性限制，按以下顺序排查：

1. **开启未知来源**：设置 → 账号与安全 → 安装未知来源应用 → 为「文件管理器」开启；
   若找不到该选项：设置 → 关于 → 连按「产品型号」6 次进入开发者模式后再找
2. **U 盘要求**：FAT32 格式，APK 放根目录，保持英文文件名
3. **提示「应用存在违规功能，禁止安装」**：这是小米云端安全扫描拦截（主要针对直播/视频类应用，
   本 App 是文件工具，被拦概率低）。被拦时改用 ADB 安装绕过：
   ```bash
   # 开发者模式中开启「ADB 调试」后
   adb connect <电视IP>
   adb install app-debug.apk
   ```
4. **无 U 盘时**：可用「小米电视助手」手机 App 推送安装
5. **授权页缺失**：MIUI TV 可能没有「所有文件访问」设置页，App 会自动降级跳转到应用详情页，
   在其中寻找存储/权限相关开关

## 已知限制（v1 明确不做）

- 断点续传（大文件传输中断需重传）
- 开机自启 / 后台常驻
- TV→PC 下载
- 外接 USB 存储写入
