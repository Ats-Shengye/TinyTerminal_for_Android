# TinyTerminal for Android 仕様書

## 概要

TinyTerminal サーバーのWebUIをAndroid WebViewで表示するネイティブクライアント。
WebSocket接続をForeground Service内のOkHttpで管理することで、アプリがバックグラウンドに移行しても接続を維持する。

### ブラウザ版との違い

ブラウザ（Chrome等）でもTinyTerminalは使えるが、以下の制約がある：

- バックグラウンドに移行するとWebSocket接続が切れる
- Androidのブラウザではカメラパンチホール・角丸への対応が不完全
- ブラウザのUI要素（アドレスバー等）が画面を圧迫する

本アプリではWebSocket接続をネイティブ側で管理し、Foreground Service + WakeLockで維持する。
サーバー側のコード変更は一切不要。

---

## アーキテクチャ

```
[Android WebView]
  ↕ JavascriptInterface（WebSocketBridge）
[TerminalService (Foreground Service)]
  ↕ OkHttp WebSocket
[TinyTerminal サーバー (PC)]
  ↕ node-pty
[シェル (bash/zsh)]
```

### WebSocket差し替え方式

サーバーの`client.js`が`new WebSocket()`を呼ぶと、通常はブラウザのWebSocket APIが使われる。
本アプリではこのWebSocket APIをshimで差し替え、Kotlin側のOkHttpに転送する。

```
1. WebView が client.js をリクエスト
2. shouldInterceptRequest でリクエストを検出
3. websocket-shim.js（WebSocket APIの差し替え）+ 元の client.js を結合して返却
4. client.js の new WebSocket() → ShimWebSocket が生成される
5. ShimWebSocket → Android.nativeConnect() → WebSocketBridge → TerminalService → OkHttp
```

### 構成要素

| レイヤー | 技術 | 役割 |
| --- | --- | --- |
| UI | WebView + サーバーのHTML/CSS/JS | ターミナル表示・入力（xterm.js含む） |
| WebSocket shim | websocket-shim.js | ブラウザWebSocket APIをJavascriptInterface経由に差し替え |
| ブリッジ | WebSocketBridge (JavascriptInterface) | JS ↔ Kotlin間のメッセージ中継 |
| 接続管理 | TerminalService (Foreground Service) | OkHttp WebSocket、WakeLock、通知 |
| ネットワーク | Tailscale | 暗号化トンネル（WireGuardベース） |

### コールバックフロー

サーバーからの応答は以下の経路で逆流する：

```
OkHttp WebSocketListener
  → TerminalService（onMessage等）
  → WebSocketCallback（MainActivity が実装）
  → WebSocketBridge.deliverMessage()
  → evaluateJavascript("window._wsShim.onNativeMessage(...)")
  → ShimWebSocket.onmessage
  → client.js のイベントハンドラ
```

---

## UI設計

### edge-to-edge表示

WebViewを画面全体に展開し、システムバー・カットアウト・IMEのインセットをFrameLayoutのpaddingで処理する。

```
┌──────────────────────────────┐
│ [カメラパンチホール]           │  ← systemInsets.top - 8dp trim
├──────────────────────────────┤
│                               │
│  WebView（サーバーのUI）       │  ← FrameLayout内、全画面展開
│  xterm.js + textarea + キーバー │
│                               │
├──────────────────────────────┤
│ [ナビゲーションバー / IME]     │  ← max(systemInsets.bottom, imeInsets.bottom)
└──────────────────────────────┘
```

### WindowInsets処理

| インセット | 対応 |
| --- | --- |
| systemBars | 上下左右のpaddingに反映 |
| displayCutout | systemBarsと合算（カメラパンチホール対応） |
| ime | キーボード表示時にbottomをIME高さに切り替え |

上部パディングはシステムインセット値から8dpを差し引く（Pixel 10 Pro Foldの実測で余白が過大だったため調整）。

### FrameLayoutラッパー

WebViewに直接setPaddingしても内部スクロールビューが無視する。
FrameLayoutで包んでそちらにpaddingを適用することで確実にインセットが反映される。

---

## Foreground Service設計

### TerminalService

| 機能 | 実装 |
| --- | --- |
| WebSocket管理 | OkHttpClient（pingInterval 30秒、readTimeout無制限） |
| 接続保持 | ConcurrentHashMap<Int, WebSocket> で複数接続管理 |
| バックグラウンド維持 | PARTIAL_WAKE_LOCK（4時間タイムアウト） |
| 通知 | IMPORTANCE_LOW チャンネル、状態表示（待機中/接続中/切断/接続エラー） |
| プロセス維持 | START_STICKY（OS kill後に再起動） |

### Service ↔ Activity通信

| 方向 | 手段 |
| --- | --- |
| Activity → Service | Binder経由でTerminalServiceインスタンス取得、WebSocketConnectionManagerとして操作 |
| Service → Activity | WebSocketCallbackインターフェース（Service.callback → MainActivityに委譲） |

---

## セキュリティ

### ホスト制限

全てのネットワークアクセスをTailscale CGNAT範囲（100.0.0.0/8）に制限する。

| チェックポイント | 実装 |
| --- | --- |
| サーバーURL入力 | `isAllowedUrl`: スキーム（http/https）+ `isTailscaleHost` |
| WebSocket接続 | `TerminalService.connect`: 接続前にホスト検証、違反時は1008で拒否 |
| client.jsインターセプト | `shouldInterceptRequest`: Tailscale IPからのリクエストのみshim注入 |

`isTailscaleHost`は正規表現 `^100\.\d{1,3}\.\d{1,3}\.\d{1,3}$` でIPv4アドレスを検証する。

### network_security_config

Androidの`<domain>`タグはIPアドレスにマッチしない制約があるため、`<base-config cleartextTrafficPermitted="true" />`を使用。
cleartext許可はconfig側で広く開けるが、コード側の3箇所のホスト検証で実質的にTailscale IPのみに制限される。

### その他の対策

| 対策 | 実装 |
| --- | --- |
| ProGuard難読化 | release buildで `isMinifyEnabled = true` |
| WakeLockタイムアウト | 4時間で自動解放 |
| キャッシュ管理 | `LOAD_NO_CACHE` + onDestroyで `clearCache(true)` |
| デバッグログ保護 | `BuildConfig.DEBUG` ガード |
| JS文字列エスケープ | `JSONObject.quote` で制御文字・Unicode安全処理 |
| バックアップ無効 | `android:allowBackup="false"` |

---

## 技術スタック

| 用途 | ライブラリ / 技術 |
| --- | --- |
| UI表示 | WebView（サーバーのHTML/CSS/JS） |
| WebSocket | OkHttp 4.12.0 |
| JS ↔ Kotlin通信 | JavascriptInterface |
| Foreground Service | Android Service + NotificationCompat |
| WakeLock | PowerManager.PARTIAL_WAKE_LOCK |
| ビルド | Gradle + Kotlin 21 |
| 最小API | 28 (Android 9) |
| ターゲットAPI | 35 (Android 15) |

### ディレクトリ構成

```
TinyTerminal_for_Android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── websocket-shim.js
│       ├── java/dev/tinyterminal/
│       │   ├── MainActivity.kt
│       │   ├── TerminalService.kt
│       │   └── WebSocketBridge.kt
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/colors.xml
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── xml/network_security_config.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── SPEC.md
├── GLOSSARY.md
└── README.md
```

---

## ビルド

```bash
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド（署名設定が必要）
```
