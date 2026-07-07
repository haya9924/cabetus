# セキュリティとプライバシーについて

cabetus は東京理科大学の**非公式**アプリです。認証情報（学籍番号・パスワード）を扱う以上、
「開発者が認証情報を取得していないこと」を利用者自身が検証できることが最も重要だと考えています。

このドキュメントは、その検証を助けるために **実装上の事実**（該当ソースの場所つき）をまとめたものです。
記載内容はすべて公開されているソースコードから確認できます。**主張を鵜呑みにせず、下記の該当ファイルを直接確認してください。**

---

## 結論（要約）

- **アプリはあなたのパスワードを一切受け取りません。** ログインは大学公式のログインページ
  （Microsoft / SAML の SSO）を**アプリ内ブラウザ（WebView）にそのまま表示**して行います。
  ID・パスワードは大学公式ページの入力欄に直接入力され、アプリのコードはそれに触れません。
- アプリが保持するのは、ログイン後に発行される **セッション Cookie** だけです。これはブラウザで
  ログインした状態を保つのと同じ仕組みで、Android のアプリサンドボックス内（端末内）に保存され、
  **大学のサーバー以外には送信されません。**
- **第三者サーバーへの認証情報・個人データの送信は行いません。** 解析 SDK・広告 SDK・
  クラッシュレポート SDK の類は一切含みません。
- **コードの難読化は行っていません**（`isMinifyEnabled = false`）。公開ソースと配布 APK を
  そのまま突き合わせて検証できます。

---

## 1. なぜ「パスワードを取得していない」と言えるのか

### ログインは WebView に大学公式ページを表示するだけ

ログイン画面 [`ui/login/LoginScreen.kt`](../app/src/main/java/org/cabetus/ui/login/LoginScreen.kt) は、
Android 標準の `WebView` に LETUS のマイページ URL を読み込むだけです。

```kotlin
loadUrl(LetusConstants.MY_URL)   // https://letus.ed.tus.ac.jp/my/
```

未ログインなら大学の SSO（`idp.admin.tus.ac.jp` → `login.microsoftonline.com`）へ自動遷移し、
ユーザーは**大学公式のログインページ**に ID・パスワードを入力します。cabetus はこのページを
**表示しているだけ**で、入力内容を読み取る処理を持ちません。

### アプリにはパスワード入力欄が存在しない

ログイン処理を担う [`ui/login/LoginViewModel.kt`](../app/src/main/java/org/cabetus/ui/login/LoginViewModel.kt)
は、ログイン完了フラグ（真偽値）を保存する `markLoggedIn()` だけを持ちます。
パスワードを受け取る関数も、保存するキーも存在しません。

### フォーム値を盗み取る仕組みが無い

WebView で認証情報を抜く典型的な手口は、`addJavascriptInterface` や `evaluateJavascript` で
入力欄（`document.querySelector('input[type=password]')` 等）の値を JavaScript 経由で読み出す方法です。
cabetus のソースには**これらの呼び出しが一切ありません**（リポジトリ全体を `addJavascriptInterface` /
`evaluateJavascript` で検索しても該当ゼロ）。WebView で JavaScript を有効化しているのは、
Microsoft SSO ページの動作に必要なためだけです。

### アプリが保持するのは Cookie のみ

ログイン後に得た Cookie は [`data/letus/WebViewCookieJar.kt`](../app/src/main/java/org/cabetus/data/letus/WebViewCookieJar.kt)
を通じて Android 標準の `android.webkit.CookieManager`（＝ブラウザと同じ Cookie ストア）に保存されます。
以降のデータ取得はこの Cookie を使って行われ、Cookie は**その Cookie が属するホスト
（大学のサーバー）にしか送信されません**（`cookieManager.getCookie(url)` によるホスト単位の送出）。

---

## 2. CLASS（時間割・出欠）はアプリ内でログインすらしない

時間割・出欠（CLASS: `class.admin.tus.ac.jp`）については、cabetus はアプリ内で認証情報を扱いません。

- 時間割・出欠データは、ユーザーが CLASS から自分でダウンロードした **PDF を手動で取り込む**方式です
  （[`data/pdf/`](../app/src/main/java/org/cabetus/data/pdf/) 配下でローカル解析。通信は発生しません）。
- 出席チェックの導線 [`notification/AttendanceCheckActivity.kt`](../app/src/main/java/org/cabetus/notification/AttendanceCheckActivity.kt)
  は、CLASS を**既定の外部ブラウザで開く**だけです（`Intent.ACTION_VIEW`）。ログインはブラウザ側で行われ、
  cabetus は関与しません。

つまり cabetus が大学の認証情報に触れる箇所は **LETUS の WebView ログイン一箇所のみ**で、
そこでもパスワードは受け取りません。

---

## 3. 通信先（送信先ホスト）の一覧

アプリがネットワーク通信を行う先は以下がすべてです（ソース内の URL 定数から機械的に列挙可能）。

| 送信先 | 用途 | 認証情報 | 備考 |
|---|---|---|---|
| `letus.ed.tus.ac.jp` および大学 SSO | LETUS の課題取得 | あなたのセッション Cookie | 大学公式サーバー |
| `class.admin.tus.ac.jp` | 出席チェックで開くだけ | なし | 外部ブラウザで開く |
| `api.open-meteo.com` | 天気予報 | なし | 匿名・緯度経度のみ。公開 API |
| ユーザーが設定した AI エンドポイント | AI コメント（任意機能） | ユーザー自身の API キー | 未設定なら通信しない（下記4） |

- 上記以外の**独自サーバーは存在しません**。開発者が運用する収集用サーバーもありません。
- すべて HTTPS です。平文通信は `AndroidManifest.xml` の `android:usesCleartextTraffic="false"` で禁止しています。
- 解析・広告・クラッシュレポートの SDK は依存関係
  （[`app/build.gradle.kts`](../app/build.gradle.kts)）に含まれていません
  （Firebase / Google Analytics / 広告 SDK いずれも不使用）。

---

## 4. AI コメント機能について（任意・オプトイン）

「今日のひとこと」AI 機能は、ユーザーが**自分で**エンドポイント URL・API キー・モデル名を設定した場合のみ動作します
（[`data/ai/AssistantRepository.kt`](../app/src/main/java/org/cabetus/data/ai/AssistantRepository.kt)）。
**設定していなければリクエストは一切発生しません。**

### AI サービスに送られる情報（これがすべて）

送信内容はプロンプト組み立て箇所（[`ui/home/HomeViewModel.kt`](../app/src/main/java/org/cabetus/ui/home/HomeViewModel.kt) /
[`work/DailySummaryWorker.kt`](../app/src/main/java/org/cabetus/work/DailySummaryWorker.kt) /
`AssistantRepository.buildPrompt()`）で確認できます。

- **日付**（例: 「7月7日（月）」）
- **天気**（例: 「晴れ 28℃」）— キャンパス単位。正確な位置情報ではありません
- **今日の授業**: 「N限 授業名（教室）」のリスト
- **未提出の課題**: 「コース名 課題タイトル（締切）」のリスト（最大8件）
- 固定のシステムプロンプト（アシスタントの人格）＋ 選択した口調プリセット・カスタム指示
- **モデル名**と、`Authorization` ヘッダに**ユーザー自身の API キー**

実際に送信されるテキストはこの形です:

```
今日は7月7日（月）です。
天気: 晴れ 28℃
今日の授業:
- 1限 微分積分学（E401）
未提出の課題（期日順）:
- 線形代数 第3回レポート（7/10 23:59）
上記を踏まえ、今日の過ごし方を励ますように…
```

### 送られないもの

- **学籍番号・氏名・パスワード・LETUS のセッション Cookie** — 一切含まれません
- 成績・出欠記録
- 設定画面の「テスト送信」は授業・課題を空（「テスト」のみ）で送ります

### 送信先について

- 送信先は**ユーザーが設定した URL のみ**で、開発者のサーバーを経由しません
  （OpenAI / OpenRouter / Gemini / ローカル LLM 等、ユーザーが選んだ事業者）。
- 使う API キーは**ユーザー自身が用意した第三者 AI サービスのキー**であり、大学の認証情報とは無関係です。
- ただし、送信後のデータの取り扱いは**選んだ AI 事業者のプライバシーポリシー次第**です（アプリの管轄外）。
  科目名・課題タイトルの送信が気になる場合は、AI 機能をオフのままにすれば送信はゼロです。

---

## 5. 端末内に保存されるもの

| 保存先 | 内容 | 機微度 |
|---|---|---|
| WebView `CookieManager` | LETUS のセッション Cookie | 高（ブラウザのログイン状態と同等） |
| DataStore（アプリ専用領域） | ログイン済みフラグ、各種設定、AI キー（設定時）、取得済みの課題・時間割 | 中 |
| Room DB（アプリ専用領域） | 課題・時間割・出欠などの取得データ | 中 |

いずれも Android の**アプリサンドボックス**により他アプリから隔離されます。
これらは端末内にのみ存在し、外部へ同期・アップロードされることはありません。

---

## 6. 要求される権限とその理由

`AndroidManifest.xml` で宣言している権限は次のとおりで、いずれも機能に直結します。

- `INTERNET` / `ACCESS_NETWORK_STATE` — LETUS からの課題取得・天気取得、通信状態の確認
- `POST_NOTIFICATIONS` — 課題締切・授業開始などの通知
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` — 授業開始通知・ウィジェットの時刻ちょうどの更新
- `RECEIVE_BOOT_COMPLETED` — 再起動後に通知・定期取得を復元
- `WAKE_LOCK` / `FOREGROUND_SERVICE` — バックグラウンド取得の補助

連絡先・位置情報・SMS・カメラ・マイクなどの権限は**要求しません**。

---

## 7. 難読化なし・再現可能性

- リリースビルドは難読化・最適化を無効にしています
  （[`app/build.gradle.kts`](../app/build.gradle.kts): `isMinifyEnabled = false`）。
  配布 APK を逆コンパイルすれば、公開ソースとほぼ 1:1 で対応する形で読めます。
- ソースは全面公開（OSS）です。指摘のあった「公式版と差異がないことを検証可能にする」方針に沿っています。

---

## 8. 既知の制約（正直な限界）

安全性を過大に主張しないため、限界も明記します。

- **セッション Cookie は「ログイン中の状態」そのもの**です。端末自体が
  マルウェア感染・root 化・盗難などで侵害された場合、ブラウザのログインセッションと同様にリスクがあります。
  これはログイン状態を保持するあらゆるアプリ／ブラウザに共通の性質です。
- DataStore / Room は**アプリ専用領域**に保存されますが、`EncryptedSharedPreferences` のような
  追加の暗号化は行っていません（端末のディスク暗号化に依存）。AI API キーも同領域に平文で保存されます。
- 非公式アプリであり、大学の公式サポート対象ではありません。利用は自己責任となります。

---

## 9. 自分で検証する方法

1. **ソースを読む** — 本書で挙げた各ファイルを直接確認してください。特に
   `LoginScreen.kt` / `LoginViewModel.kt` / `WebViewCookieJar.kt` / `NetworkModule.kt`。
2. **通信を観察する** — mitmproxy / Charles / PCAPdroid などで実機の通信を記録し、
   送信先が上表のホストに限られること、認証情報が第三者へ出ていないことを確認できます。
3. **自分でビルドする** — リポジトリを clone し `./gradlew assembleRelease` でビルドした APK と、
   配布 APK の挙動を比較できます（難読化なしのため差分確認が容易です）。
4. **逆コンパイルする** — jadx 等で配布 APK を逆コンパイルし、公開ソースと突き合わせられます。

---

## 10. 脆弱性・懸念の報告

セキュリティ上の懸念や問題を見つけた場合は、GitHub の Issue、または開発者への連絡でご報告ください。
指摘は歓迎します。事実誤認や改善提案があれば、本ドキュメントも随時更新します。
