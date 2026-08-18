# TinyTerminal for Android

TinyTerminal サーバーに接続するAndroidネイティブクライアント。

- WebView + WebSocket shim によるサーバーUI完全再利用
- Foreground Service でバックグラウンド接続維持
- Tailscale IP ホワイトリストによるアクセス制御
- セキュリティレビュー 9.0/10 PASS

> 技術詳細は [GLOSSARY.md](./GLOSSARY.md) を参照

## 概要

[TinyTerminal](https://github.com/Ats-Shengye/TinyTerminal) サーバーのWebUIをAndroid WebViewで表示しつつ、
WebSocket接続をネイティブ側（OkHttp）で処理することで、Foreground Serviceによるバックグラウンド接続維持を実現。

サーバーのclient.jsをインターセプトし、WebSocket APIをJavascriptInterface経由のshimに差し替える設計のため、
サーバー側の変更は不要。

## 必要環境

- Android 9+ (API 28+)
- Tailscale VPN
- TinyTerminal サーバー

## ビルド

```bash
./gradlew assembleDebug
```

APKは `app/build/outputs/apk/debug/` に出力。

## 使い方

1. TinyTerminal サーバーを起動（`BIND_ADDRESS=0.0.0.0 npm start`）
2. アプリ初回起動時にサーバーURL入力（例: `http://100.64.0.1:3000`）
3. Tailscale IP（100.x.x.x）のみ接続許可

## ライセンス

MIT
