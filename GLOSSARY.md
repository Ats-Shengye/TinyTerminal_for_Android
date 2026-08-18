# Glossary

本プロジェクトの全クラス・関数・インターフェース・定数・セキュリティ対策の一覧。
コードリーディングの補助資料として使用。

updated: 2026-02-13

## クラス・インターフェース

| 名前 | 種別 | ファイル | 役割 |
| --- | --- | --- | --- |
| `MainActivity` | Activity | MainActivity.kt | WebView管理、Service bind、URL入力、WindowInsets処理 |
| `TerminalService` | Foreground Service | TerminalService.kt | OkHttp WebSocket接続管理、WakeLock、通知表示 |
| `WebSocketBridge` | クラス | WebSocketBridge.kt | JS ↔ Kotlin間のWebSocketメッセージ中継（JavascriptInterface） |
| `WebSocketConnectionManager` | インターフェース | WebSocketBridge.kt | WebSocket接続操作の抽象化（connect/send/close） |
| `WebSocketCallback` | インターフェース | WebSocketBridge.kt | WebSocketイベントのコールバック（open/message/close/error） |
| `ShimWebSocket` | JSクラス | websocket-shim.js | ブラウザWebSocket APIの差し替え実装（→ Android.native*を呼ぶ） |

## MainActivity メソッド

| 名前 | 役割 |
| --- | --- |
| `applyWindowInsets` | edge-to-edge表示のためのシステムバー・カットアウト・IMEインセット処理 |
| `setupBridge` | WebSocketBridgeの生成とJavascriptInterface登録 |
| `setupWebView` | WebView設定（JS有効化、キャッシュ無効、client.js インターセプト） |
| `startAndBindService` | TerminalServiceの起動とbind |
| `getServerUrl` | SharedPreferencesからサーバーURL取得 |
| `saveServerUrl` | SharedPreferencesにサーバーURL保存 |
| `isAllowedUrl` | URL入力のバリデーション（スキーム + Tailscale IPホワイトリスト） |
| `isTailscaleHost` | IPアドレスがTailscale CGNAT範囲（100.x.x.x）か判定 |
| `showServerUrlDialog` | サーバーURL入力ダイアログ表示 |
| `loadUrl` | WebViewにURLをロード |

## TerminalService メソッド

| 名前 | 役割 |
| --- | --- |
| `connect` | WebSocket接続開始（ホスト検証 → OkHttp WebSocket生成） |
| `send` | WebSocketメッセージ送信 |
| `close` | WebSocket接続切断 |
| `acquireWakeLock` | PARTIAL_WAKE_LOCK取得（4時間タイムアウト） |
| `createNotificationChannel` | 通知チャンネル作成（IMPORTANCE_LOW） |
| `buildNotification` | Foreground Service通知の構築 |
| `updateNotification` | 通知テキスト更新（待機中/接続中/切断/接続エラー） |

## WebSocketBridge メソッド

| 名前 | 種別 | 役割 |
| --- | --- | --- |
| `nativeConnect` | @JavascriptInterface | JS → Kotlin: WebSocket接続要求 |
| `nativeSend` | @JavascriptInterface | JS → Kotlin: メッセージ送信 |
| `nativeClose` | @JavascriptInterface | JS → Kotlin: 接続切断 |
| `deliverOpen` | コールバック配信 | Kotlin → JS: 接続成功通知 |
| `deliverMessage` | コールバック配信 | Kotlin → JS: メッセージ配信（JSONObject.quote） |
| `deliverClose` | コールバック配信 | Kotlin → JS: 切断通知 |
| `deliverError` | コールバック配信 | Kotlin → JS: エラー通知 |

## websocket-shim.js

| 名前 | 役割 |
| --- | --- |
| `ShimWebSocket` | ブラウザのWebSocket APIを差し替え、Android.native*経由でKotlin側に転送 |
| `window._wsShim.onNativeOpen` | Kotlin → JS: 接続成功コールバック受信 |
| `window._wsShim.onNativeMessage` | Kotlin → JS: メッセージコールバック受信 |
| `window._wsShim.onNativeClose` | Kotlin → JS: 切断コールバック受信 |
| `window._wsShim.onNativeError` | Kotlin → JS: エラーコールバック受信 |

## 定数

| 名前 | 値 | 場所 | 役割 |
| --- | --- | --- | --- |
| `PREFS_NAME` | `"TinyTerminalPrefs"` | MainActivity | SharedPreferencesファイル名 |
| `SERVER_URL_KEY` | `"server_url"` | MainActivity | サーバーURL保存キー |
| `CHANNEL_ID` | `"tinyterminal_service"` | TerminalService | 通知チャンネルID |
| `NOTIFICATION_ID` | `1` | TerminalService | Foreground Service通知ID |

## データフロー

| 方向 | 経路 |
| --- | --- |
| 入力（JS → サーバー） | client.js → ShimWebSocket.send → Android.nativeSend → WebSocketBridge → TerminalService → OkHttp WebSocket |
| 出力（サーバー → JS） | OkHttp WebSocket → TerminalService → MainActivity(callback) → WebSocketBridge.deliverMessage → evaluateJavascript → window._wsShim → ShimWebSocket.onmessage → client.js |
| client.js差し替え | shouldInterceptRequest: client.jsリクエスト検出 → websocket-shim.js + 元のclient.jsを結合して返却 |

## セキュリティ対策

| 項目 | 実装 |
| --- | --- |
| URL入力バリデーション | `isAllowedUrl`: スキーム（http/https）+ Tailscale IPホワイトリスト |
| WebSocket接続先検証 | `TerminalService.connect`: 非Tailscaleホストは1008 Policy violationで拒否 |
| client.jsインターセプト検証 | `shouldInterceptRequest`: ホスト検証済みのTailscale IPからのみshim注入 |
| Cleartext対策 | `network_security_config` + コード側ホスト検証（domain-configはIPに非対応のため） |
| ProGuard難読化 | release buildで `isMinifyEnabled = true` |
| WakeLockタイムアウト | 4時間で自動解放（無期限保持防止） |
| キャッシュ管理 | `LOAD_NO_CACHE` + onDestroyで `clearCache(true)` |
| デバッグログ保護 | `BuildConfig.DEBUG` ガードで本番ビルドのログ出力抑制 |
| JS文字列エスケープ | `JSONObject.quote` で制御文字・Unicode・ANSIエスケープを安全に処理 |
