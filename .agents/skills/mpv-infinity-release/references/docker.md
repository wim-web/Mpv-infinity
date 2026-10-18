# Dockerビルド

この手順は2026-10-19に `standard` / `arm64-v8a` / debug のAPK生成と単体テストに使用したもの。将来のSDK・NDK・Gradle指定はリポジトリの実値を確認する。

## 環境

- 既存の `mpv-infinity-android-build:local` イメージと、`mpv-infinity-android-sdk`・`mpv-infinity-gradle-cache` ボリュームを再利用できる。
- イメージがない場合は [Dockerfile](../assets/Dockerfile) を使う。Apple Silicon上でも、Linux Android SDK/NDKの実行ファイルに合わせて `--platform linux/amd64` で構築・実行する。
- DockerfileにはJDK 21、Android command-line tools、autotools、`autopoint`、gettext、gperfなどが含まれる。SDKのCMakeディレクトリをPATHに入れておく。字幕ライブラリのネストしたビルドも `ninja` を必要とする。
- Dockerの認証ヘルパーが公開イメージ取得で停止する場合は、一時ディレクトリの空のDocker設定と、`docker context inspect` で取得した現在のDockerエンドポイントを使える。ユーザーの通常の認証設定を変更しない。

## 実行の形

新規イメージはスキルの `assets/` をビルドコンテキストにする。

```sh
docker build --platform linux/amd64 -t mpv-infinity-android-build:local <スキルのassetsディレクトリ>
```

コンテナに作業リポジトリを `/workspace`、`.git` を同じ位置にread-onlyでマウントする。Gradleキャッシュは `/root/.gradle`、SDKボリュームは `/opt/android-sdk`。必要な一時ファイルだけを追加でマウントする。

成功時のSDKインストール指定は次のとおり。API 37の配布名は `android-37` ではなく `android-37.0` だった。

```sh
sdkmanager 'platforms;android-37.0' 'platforms;android-35' 'build-tools;37.0.0' 'ndk;27.3.13750724' 'cmake;3.22.1'
```

字幕モジュールは追加でNDK 28.2.13676358などを自動取得した。環境設定を省くためにアプリのcompileSdkや依存バージョンを下げない。

コンテナ内の実行コマンド:

```sh
./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug \
  -PtargetAbi=arm64-v8a -PenableX86=false \
  -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false \
  --continue --no-watch-fs --no-daemon --console=plain --stacktrace
```

- `android.injected.build.abi` によってテスト専用APKになる場合があるため、配布用は `-Pandroid.injected.testOnly=false` を必ず指定する。debug署名と `testOnly` は別の設定。
- `--no-watch-fs` はDockerの共有ファイルシステム上でのファイル監視エラーを避ける。
- Maven Centralが極端に低速な場合は [mirror.init.gradle](../assets/mirror.init.gradle) をコンテナにread-onlyでマウントし、`-I <マウント先>` を加える。Google提供のMaven Centralミラーをその実行だけ使用し、リポジトリの依存設定は変更しない。
- コンテナ名は既存の利用状況を確認して決める。完了したこの作業用コンテナだけを再利用・整理し、他のコンテナには触れない。

## 成果物確認

- `android.injected.build.abi` 使用時のAPKは `app/build/intermediates/apk/standard/debug/app-standard-arm64-v8a-debug.apk` に生成された。`outputs/apk` にないだけで失敗と判断せず、実際の出力とメタデータを確認する。
- 渡しやすい場所として `app/build/outputs/apk/standard/debug/` にコピーできる。
- SDKの `apksigner verify --verbose`、`aapt dump badging`、ZIP内の `lib/` で署名・アプリID・ABIを確認する。
- **配布前に** `aapt dump xmltree <APK> AndroidManifest.xml` で `android:testOnly` が不在またはfalseであることを確認する。trueのAPKは通常のインストーラーで入れられないため公開しない。署名検証だけではこの不備を検出できない。
- 単体テストは `app/build/test-results/testStandardDebugUnitTest/TEST-*.xml` を集計する。過去の成功件数を固定値として報告しない。
- Roomが `app/schemas/` に新しいスナップショットを出力する場合がある。今回のビルドだけで生成された無関係なものと確認できた場合は `app/build/verification/` などへ移し、機能変更のコミットに混ぜない。
- ビルド成功と実機での画面オフ再生などの動作確認は区別する。ただし、この検証情報をリリース本文へ自動追加しない。
