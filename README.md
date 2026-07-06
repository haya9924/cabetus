<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png" width="120" alt="cabetus icon">

# cabetus

**東京理科大学の学生生活を1つのアプリに。**

LETUS の課題と CLASS の時間割・出欠をまとめて表示する、理科大生（全学部）向けの非公式 Android アプリです。
神楽坂・葛飾・野田、どのキャンパスでも使えます。

`β1.0`

[<img src="https://raw.githubusercontent.com/machiav3lli/oandbackupx/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="70">](../../releases)
[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="70">](https://github.com/ImranR98/Obtainium)


</div>

---

## ✨ 機能

### 🏠 ホーム
- キャンパス（神楽坂 / 葛飾 / 野田）の**天気予報**
- 時間割から算出した**今実施中・次の授業**（時限・教室つき）
- OpenAI互換APIによる **AI「今日のひとこと」** — 今日の授業と残課題を踏まえたアドバイス
- **残っている課題**を期日順に一覧表示

### 📚 課題
- **LETUS から課題を自動取得**（課題・小テスト・Turnitin ほか）
- 期日順 / 検出順ソート、**科目・提出状況での絞り込み**
- 提出済み・期限切れバッジ、不要な課題の非表示
- 取得したデータはアプリ内の共有データベースに保存され、ホーム・ウィジェット・通知からも参照

### 🗓 時間割
- CLASS の「学生時間割表」PDF を取り込んで**週間グリッド表示**（今日・現在の時限をハイライト)
- 「学生出欠状況表」PDF から**出席率・出欠マーク・学期の全授業日程**を取得
- 科目タップで教員・教室・単位・出席状況を表示、**公開シラバス**もアプリ内で閲覧

### 📱 ホーム画面ウィジェット
- 残課題の期日一覧をホーム画面に常時表示
- **背景の不透明度（0〜100%）と配色**（Material You ダイナミック / ライト / ダーク / カラー3種）をウィジェットごとに設定可能

### 🔔 通知（すべて個別チャンネル）
- AIによる**今日のまとめ**（時刻指定のデイリー通知）
- **授業開始5分前**のお知らせ — 「出席にカウント」ボタンで出席を記録し、既定ブラウザで CLASS を開く
- **課題の期日**（24時間 / 3時間 / 1時間前）
- **新規課題**の検出
- **取得失敗**（要再ログイン時はタップでログイン画面へ）

### ⚙️ 設定
- 自動取得の頻度（毎時 / 毎日+時刻）、**モバイルデータ通信の許可**、見送り回数による強制取得
- ログイン状態の確認・再ログイン、PDF再インポート、キャンパス変更
- テーマ（システム / ライト / ダーク、**Material You** ダイナミックカラー対応）
- AI（OpenAI互換API）のベースURL・APIキー・モデル設定とテスト送信
- 通知の種類ごとのオン/オフ、バージョン表示

### 🚀 初回セットアップ
通知許可 → LETUSログイン（内蔵ブラウザ）→ PDF取込 → キャンパス選択 → AI設定 → 完了（X への投稿ボタン付き）を順に案内。PDF・AI はスキップ可能です。

---

## 🔐 しくみ・プライバシー

- LETUS へのログインは**アプリ内の WebView 上で大学の SSO（Shibboleth / Microsoft）に直接**行い、パスワードはアプリに保存しません。取得した Cookie は端末内にのみ保持されます。
- 課題・時間割・出欠などのデータはすべて**端末内のデータベース**に保存されます。外部サーバには送信しません（AI機能を設定した場合のみ、今日の授業・課題の要約があなたが指定したAPIへ送られます）。
- 時間割・出欠は CLASS からエクスポートした PDF を手動で取り込む方式のため、CLASS のパスワードも不要です。

## 🛠 技術スタック

Kotlin / Jetpack Compose / Material 3（Material You）/ Hilt / Room / DataStore / WorkManager / AlarmManager / OkHttp + Jsoup / kotlinx.serialization / Jetpack Glance / pdfbox-android

- 課題取得ロジックは [waiteu-git/lms-task-watcher](https://github.com/waiteu-git/lms-task-watcher) を参考に移植し、実際の LETUS 認証フロー（Shibboleth → idp.admin.tus.ac.jp → Microsoft ログイン）の計測結果に基づいて調整しています。
- コース列挙は Moodle の AJAX API（sesskey）を第一経路とし、HTML走査にフォールバックします。

## 🔨 ビルド

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug        # デバッグAPK
./gradlew :app:testDebugUnitTest    # ユニットテスト
```

- パッケージ: `org.cabetus` / minSdk 26 / compileSdk 35
- Gradle は JDK 21 が必要です（`gradle.properties` の `org.gradle.java.home` を環境に合わせて変更してください）

> ⚠️ `app/src/test/resources/` の PDF フィクスチャは**個人情報を含む実データ**のためリポジトリに含まれません（`.gitignore` 済み）。無い環境では該当テストは自動的にスキップされます。

## ⚠️ 免責

cabetus は**非公式**アプリであり、東京理科大学とは一切関係ありません。LETUS・CLASS の仕様変更により動作しなくなる可能性があります。出欠・課題の情報は必ず公式サイトでも確認してください。
