# Mpv∞

[ZHINFINITY/Mpv-infinity](https://github.com/ZHINFINITY/Mpv-infinity) をベースにした Android メディアプレイヤーです。MPV と AndroidX Media3 による再生機能を備えています。

## ローカルビルド

必要な環境:

- JDK 17、Git
- Android SDK（アプリの compileSdk 37、subtitle-libass の compileSdk 35）
- Android NDK 27.3.13750724、CMake 3.22.1
- libass のビルド用ツール: autoconf、automake、libtool、gettext（autopoint）、gperf、pkg-config

Android SDK の場所は `local.properties` の `sdk.dir` または `ANDROID_HOME` で指定します。初回ビルドには依存ライブラリと libass ソースを取得するネットワーク接続が必要です。

```sh
# ARM64 端末用のデバッグ APK
./gradlew :app:assembleStandardDebug -PtargetAbi=arm64-v8a

# ユニットテスト
./gradlew :app:testStandardDebugUnitTest
```

APK の出力先は `app/build/outputs/apk/standard/debug/` です。全対応 ABI をビルドする場合は `-PtargetAbi` を省略します。Windows では `gradlew.bat` を使用します。

`standard` のほかに `fongmi` と `noVulkan` のビルド設定も残しています。
GitHub Actions を利用する場合は、**Build debug APK** を手動実行して APK を取得できます。

## 資料と構成

- `app/`: アプリ本体、再生用ライブラリ、リソース、テスト
- `subtitle-libass/`: Media3 用の libass 字幕モジュール
- [カスタムコマンド](MPV_INFINITY_CUSTOM_COMMANDS.md)
- [カタログ・ストリーミング設計](docs/catalog-streaming.md)
- [ライセンス](LICENSE) / [第三者ライセンス](THIRD_PARTY_LICENSES/)

公開配布用のメタデータ・宣伝画像、寄付・Issue 受付設定、リリース・プレビュー公開と用途限定のビルドワークフロー、上流の変更履歴・コントリビューションガイドを整理しています。アプリ本体、テスト、翻訳、同梱ライブラリは維持しています。

## Acknowledgments

Mpv∞ is maintained by **ZHINFINITY** and follows the same open-source lineage model as mpvRx: it is an independent modified Android player based on the open-source **[mpvRx](https://github.com/Riteshp2001/mpvRx)** project, with the earlier **[mpvExtended / mpvEx](https://github.com/marlboro-advance/mpvEx)** and **[mpv-android](https://github.com/mpv-android)** projects acknowledged as part of that lineage. Mpv∞-specific branding, Native Media3 integration, engine routing, fallback handling, controls, themes, and other changes are maintained separately. Mpv∞ is not affiliated with or endorsed by the upstream maintainers.

- [mpv-android](https://github.com/mpv-android)
- [AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3) for the Native/Media3 playback, audio, UI, and effects stack (**Apache-2.0**).
- [Jellyfin Media3 FFmpeg Decoder](https://github.com/jellyfin/jellyfin-androidx-media) through `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` for additional codec and multichannel audio support (**GPL-3.0**).
- **Device-dependent 7.1 audio support** is Mpv∞ integration built on the Media3 audio pipeline, the Jellyfin FFmpeg decoder where selected, and codecs exposed by the user’s Android device; it is not a separate third-party library or a universal device-support guarantee.
- [mpvExtended / mpvEx](https://github.com/marlboro-advance/mpvEx)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [PixelPlayer](https://github.com/theovilardo/PixelPlayer)
- [MpvRex](https://github.com/sfsakhawat999/mpvRex)
- [Next Player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)
- [hdr-toys](https://github.com/natural-harmonia-gropius/hdr-toys)
- [AFinity](https://github.com/MakD/AFinity)
- [anacrolix/torrent](https://github.com/anacrolix/torrent)
- [Anime4K](https://github.com/bloc97/Anime4K)
- [**SunnyVishnu3**](https://github.com/SunnyVishnu3) for the `yt-dlp` native integration and SDK 29+ bypass logic.

Individual source files and bundled assets may contain their own copyright and license notices; those notices remain part of the distributed source tree.

---

## License

Distributed under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**. See [`LICENSE`](LICENSE) for the license and attribution information. Mpv∞-specific code, branding, and modifications are maintained by **ZHINFINITY**, while upstream copyrights remain with their respective holders.
