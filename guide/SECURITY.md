# セキュリティとプライバシーについて

## ホーム表示中の自動モバイル出席確認（現行）
ホームがRESUMEDかつ授業中で接続が判明している場合、最短60秒間隔で既存dataSyncサービスのモバイル読取モードを開始します。非表示になった後の新規定期実行・WorkManager・自動ログインはありません。1回最大60秒、仮想画面WebViewは1つで通常ブラウザ／出欠巡回と排他します。実機確認したスマートフォンENTERと「モバイル出席登録」の一意な可視リンクだけ操作し、可視性・正確なホスト／パス・DOM・見出し日付を検証。入力値・コード・hiddenは読まず、出席送信しません。日付は実機で確認した独立の可視H1を含む見出しのみから抽出し、端末日付で補完しません。結果は既存のメモリ内repositoryへ渡し、失敗時は破棄。認証切れは手動ブラウザ操作後まで自動再試行を抑止します。画面の状況は大学の出席履歴とは別で、時刻／対応／15分有効期限／検出可能な回線種別変更の検証を維持します。以下の「明示ボタンのみ」という旧説明よりこの節を優先します。

## 仮想画面のCLASS巡回（現行）
ユーザーが時間割の更新を押した場合、非exportedのdataSyncサービスが1つのWebViewを非公開仮想画面のPresentationへ接続して実行します。物理画面のミラーリング、オーバーレイ、画面キャプチャ権限は使用しません。2枚の描画バッファは直ちに破棄し、画像保存はしません。既存のdocument可視性・年度学期・科目同一性・一覧復帰チェックを維持し、実際に描画して検証します。600秒の外側制限、通知の中止、メモリ危機、renderer消失、終了時にWebView/Presentation/display/bufferを解放。同じアプリ内の通常ブラウザと同時には実行しません。他アプリ/別端末のCLASS閲覧は排他できず、CLASSが画面競合を返す場合は停止します。ログイン・出席入力/送信は自動化せず、秘密情報をログへ出しません。13科目238回の非表示取得・Home移動中継続・サービス終了を実機確認済み。画面消灯、プロセス強制終了、全メモリ圧力ケースは未保証。native JSF workerは引き続き無効です。以下の前景のみという旧制限より本節を優先します。

## CLASS出欠更新の現行仕様（下記の旧プレビュー制限より優先）
時間割のリロードを明示的に押した場合だけ、前景WebViewで検証済みのスマートフォン入口・出欠確認メニュー・科目リンク・戻るを操作します。ログイン操作・出席送信は行いません。通常閲覧の実験的巡回フラグは無効のままです。選択中年度学期を一覧・詳細・戻り先で検証し、一覧と詳細の正式コード一致、日付/曜日/単一時限が確定した科目だけ専用Roomテーブルへ保存します。パスは実機確認済みBsa501/Kmd508/Xut613に限定。年度内の1〜3月は翌暦年として曜日整合も検証します。入力欄・hidden/ViewState・Cookieの値はDOM抽出しません。中止後の新しい保存を抑止し、成功した科目だけ保持します。ページ送り検出時は停止します。既存時間割/PDF/手動修正/local記録は変更せず、仮コード対応は明示確認した年度別mappingを読取時のみ適用します。13科目238回の取得保存・再実行重複なし・既存手動修正保持を実機確認済みです。別学期切替、未知のページ送り、全ログイン状態、中止の全タイミングは未検証です。


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
cabetus は `addJavascriptInterface` を一切使用しておらず、Android 側の関数をページへ公開していません。
`evaluateJavascript` の利用箇所は、次の 6 つだけです。いずれも**固定文字列**のスクリプトを
実行し、`addJavascriptInterface` による Android ブリッジは公開しません。

1. CLASS のスマートフォン表示で `#menuPanel` の高さが 0 になる不具合の補正
   （[`ui/browser/ClassMenuPanelFix.kt`](../app/src/main/java/org/cabetus/ui/browser/ClassMenuPanelFix.kt)）。
   `https://class.admin.tus.ac.jp`（完全一致ホスト）のページ表示後に、`#menuPanel` の inline style
   （height / min-height / max-height）を `window.innerHeight` に合わせるだけです。
2. 学生時間割表ページを表示している間の、取り込みボタン表示用の DOM 再検証
   （[`data/classweb/ClassTimetableDomExtractor.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassTimetableDomExtractor.kt)）。
   URL が対象パスでも、成績一覧など同じ URL を使う別画面があります。そのため
   `https`・`class.admin.tus.ac.jp`（完全一致ホスト）・対象パス（完全一致のみ）に加えて、
   ページが表示状態であること、`.rishuArea` の最初の表が実際に表示されていること、
   月〜土のヘッダーがあることを確認します。返すのは成功/失敗とエラーコードだけで、
   科目名などの実データは返しません。DOM の変化・SPA 的な画面更新・非表示化に追従し、
   確認できなくなった時点でボタンを隠します。
3. ユーザーが明示的に「この時間割を取り込む」を押したときの、学生時間割表の DOM 読み取り
   （[`data/classweb/ClassTimetableDomExtractor.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassTimetableDomExtractor.kt)）。
   クリック時にも 2 と同じ URL・表示状態・表・ヘッダーを再検証し、対象外なら抽出しません。
   次のいずれかの学生時間割表パス
   （完全一致のみ。前方一致・ディレクトリ単位では許可しません）を確認し、
   - `/uprx/up/at/ats501/Ats50101.xhtml`
   - `/uprx/up/bs/bsa501/Bsa50101.xhtml`（実機で表示を確認済み）

   `.rishuArea` の最初の表に月〜土のヘッダーがあることを確認したうえで、
   `td.colJigen` の時限と `a.jugyo-info` の `textContent`（科目名）、およびリンクの
   `onclick` が指す `div.jugyoInfoArea` の直接子から科目コード・教室だけを JSON で返します。
   科目詳細は開閉状態に関係なく DOM に存在するため、開閉操作は行いません。
   科目名と詳細の `span.jugyoName` が一致しない場合や、直接子が
   `span.jugyoName`・教員・教室・科目コード・単位の順に確認できない場合は値を推測せず空にします。
   同じ科目名で異なる正式コードが確認できた場合や、同名の一部でコード欠落がある場合は、
   同じ仮コードへ統合せず対象を保留します。昼休みの colspan 行は対象外です。
   Cookie・localStorage・sessionStorage・hidden input・フォーム値・ページ全体の HTML は
   読み取りません。返却された科目名等はプレビュー表示後、ユーザーが保存を確定した場合だけ
   端末内 Room に追加します（ログ出力はしません）。
4. 出欠詳細ページを表示している間の、読み取りボタン表示用の DOM 再検証
   （[`data/classweb/ClassAttendanceDetailDomExtractor.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassAttendanceDetailDomExtractor.kt)）。
   URL パスは実機で確認できていないため許可リストにせず、`https`・`class.admin.tus.ac.jp`
   （完全一致ホスト）・ページが表示状態であること・ページに可視の出欠見出しに「出席」または
   「出欠」を含むこと（実機では見出しが `.mainContent` の外の h 系 / `.ui-title` にあるため
   `.mainContent` 内を優先しつつページ全体からも探す）・
   `div[id$=jugyoKaisuTbl].ui-datatable.attendanceInfo` が一意であること・
   必須ヘッダー（回数・実施日・曜日時限・出欠・試験）が揃っていることを DOM で確認します。
   ページ全体のテキストは読みません。返すのは成功/失敗とエラーコードだけで、
   セル内容や見出し文字列は返しません。
5. ユーザーが明示的に「この科目の出欠を読み取る」を押したときの、出欠詳細テーブルの DOM 読み取り
   （[`data/classweb/ClassAttendanceDetailDomExtractor.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassAttendanceDetailDomExtractor.kt)）。
   クリック時にも 4 と同じ条件を再検証したうえで、対象テーブルのヘッダーとセルの
   `textContent` だけをサニタイズ済み HTML として返します。プレビューの見出しは
   実機で確認した既知の固定ラベル（「学生出欠状況確認」「出欠状況確認」、それ以外は「出欠詳細」）
   に限定し、画面固有の見出し文字列（科目名・氏名などを含み得る）は返しません。
   科目コードは抽出しません（画面上の 7 桁英数字を正式な科目コードと推測しないため）。
   Cookie・localStorage・sessionStorage・フォーム値・hidden input・ページ全体の HTML は
   読み取りません。読み取った内容は画面のプレビューにだけ使い、**データベースには保存しません**
   （ログ出力もしません）。
6. CLASS のモバイル出席登録ページを検証し、表示中の出席を自動または手動で読み取る処理
   （[`data/classweb/ClassMobileAttendanceDomExtractor.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassMobileAttendanceDomExtractor.kt)
   の `VERIFY_SCRIPT` / `SCRIPT`）。`https`・`class.admin.tus.ac.jp`（完全一致ホスト）・
   実機で確認済みのモバイル出席登録パス（完全一致のみ）・ページが表示状態であること・可視見出し
   （「モバイル出席登録」「出席登録」）・`.mainContent` 内の `#…dataList` とその直接の子 `.dataAll`・
   `label.textOf.sizeBig` などの既知要素を DOM で確認します。返すのは授業ブロックごとの科目名・
   受付時間・「出席」/「出席確認中」の表示・ページ日付だけです。出席コードの入力欄（`maxlength=1` の
   text input 等）の値・IP アドレス・Cookie・localStorage・sessionStorage・フォーム値・hidden input・
   ページ全体の HTML は読みません。ホーム用の結果はメモリだけに保持します。
   科目・日付・時限を一意に特定できた「出席」のみ、別途 Room の出席履歴へ保存します。

いずれも入力欄の読み取り、Cookie・ストレージの読み出し、外部への送信は行いません。

出欠一覧を自動巡回する未完成のコード（[`ui/browser/ClassAttendanceTourRunner.kt`](../app/src/main/java/org/cabetus/ui/browser/ClassAttendanceTourRunner.kt)、
[`data/classweb/ClassAttendanceListDomExtractor.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassAttendanceListDomExtractor.kt)
の一覧・クリック・「戻る」スクリプト）はリポジトリに含まれていますが、
`ClassAttendanceTourFeature.ENABLED = false` の間は**一度も実行されません**。
無効化の対象は通常のブラウザにある実験的な巡回操作です。時間割画面から利用者が開始するバックグラウンド出欠更新とは別です。

モバイル出席は検証済みの表示中ページを自動で読み取ります。ホームでも授業中・接続時に確認します。

### アプリが保持するのは Cookie のみ

ログイン後に得た Cookie は [`data/letus/WebViewCookieJar.kt`](../app/src/main/java/org/cabetus/data/letus/WebViewCookieJar.kt)
を通じて Android 標準の `android.webkit.CookieManager`（＝ブラウザと同じ Cookie ストア）に保存されます。
以降のデータ取得はこの Cookie を使って行われ、Cookie は**その Cookie が属するホスト
（大学のサーバー）にしか送信されません**（`cookieManager.getCookie(url)` によるホスト単位の送出）。

---

## 2. CLASS（時間割・出欠）もアプリ内で認証情報を扱わない

時間割・出欠（CLASS: `class.admin.tus.ac.jp`）についても、cabetus はパスワードを受け取りません。

- PDF取り込み機能は廃止しました。既存の取り込み済みデータは削除しません。年度は設定画面で変更します。
- セットアップ完了後にアプリを表示している間、時間割が空なら、既存の非公開仮想画面WebViewを使って初回取り込みを開始します。既知のCLASS入口・学生時間割表メニューだけを操作し、URLと表示中の時間割表を検証してから既存の取り込み処理で保存します。保存時にも時間割が空であることを確認し、年度・学期は推測せず、ログイン・出席登録・出欠巡回は行いません。
- 手動取り込みについては、アプリ内ブラウザで学生時間割表の表示を検証した後、画面単位で一度自動的に
  読み取りプレビューを作成します。画面に表示されている科目名・曜日・時限、および科目詳細で確認できる科目コード・教室だけを
  固定スクリプトで読み取ります。科目名と詳細が一致しない場合や、詳細の項目の並び・コード・教室が
  不明・重複・不一致の場合は推測しません。同名で異なる正式コードがある場合などは仮コードで統合せず
  対象を保留します。読み取り結果はプレビューで確認してから保存し、既存の時間割を削除する clear や
  既存科目の上書きは行いません。年度・学期は CLASS の時間割画面から取得できないため、混在を避ける
  よう画面上で注意を表示します。
- 出席チェックや「CLASS を開く」導線は、アプリ内ブラウザ
  [`ui/browser/`](../app/src/main/java/org/cabetus/ui/browser/) で CLASS を開きます。
  CLASS のログインは大学公式の SSO ページを WebView に表示して行われるため、cabetus は入力内容を
  読み取りません。Cookie は LETUS と同様に `CookieManager` に保持され、CLASS のホストにのみ送信されます。
- 出欠詳細は、アプリ内ブラウザで**表示中の1科目だけ**を明示ボタンで読み取り、内容を確認する
  **プレビュー専用**です（[`ui/browser/ClassAttendanceDetailImportViewModel.kt`](../app/src/main/java/org/cabetus/ui/browser/ClassAttendanceDetailImportViewModel.kt)、
  [`data/classweb/ClassAttendanceDetailImportRepository.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassAttendanceDetailImportRepository.kt)）。
  画面に保存ボタンはなく、リポジトリの保存関数も常に拒否するため、**どの経路からも Room へ
  出欠詳細を取り込みません**。既存の出欠データも変更しません。**全科目の自動巡回・定期同期は未対応**です。
  実機の実施日は年を含まない MM/DD 表記のため年を推測せず、画面上の 7 桁英数字を
  正式な科目コードとして扱いません。
- 出欠一覧を自動で巡回する実験的な処理（一覧の科目リンクのクリック → 詳細の読み取り → 「戻る」の反復）は
  **未完成・実機未検証**のため、既定では**完全に無効**です
  （[`ui/browser/ClassAttendanceTourFeature.kt`](../app/src/main/java/org/cabetus/ui/browser/ClassAttendanceTourFeature.kt)
  の `ENABLED = false`）。無効の間は、画面に巡回の開始ボタン・進捗・中止ボタン・結果ダイアログを
  一切表示せず、[`ui/browser/ClassAttendanceTourRunner.kt`](../app/src/main/java/org/cabetus/ui/browser/ClassAttendanceTourRunner.kt)
  も生成しません（一覧の再検証スクリプトも実行しません）。有効化は、一覧の件数・並び・リンク先が
  巡回中に変わらないこと、詳細 DOM が実際に切り替わってから読み取れること、「戻る」で一覧に戻れること、
  中止・画面破棄で即座に停止できることを実機で確認した後に行います。
  **時間割の取り込みと出欠詳細の単科プレビューはこの無効化の対象外**で、従来どおり利用できます。
- CLASS のスマートフォン版「モバイル出席登録」から出席表示を読み取る処理
  （[`data/classweb/ClassMobileAttendanceDomExtractor.kt`](../app/src/main/java/org/cabetus/data/classweb/ClassMobileAttendanceDomExtractor.kt)、
  [`data/classweb/MobileAttendanceRepository.kt`](../app/src/main/java/org/cabetus/data/classweb/MobileAttendanceRepository.kt)）は、
  完全一致パスのページで DOM（見出し・授業一覧）を確認できた場合に読み取ります。画面に曜日時限が無いため、
  次の 3 つが揃ってコマを**一意に推定**できた場合だけ時間割と紐付けます。
  - ページの**年あり**日付から求めた曜日（年なし・実在しない日付・曜日表記の矛盾は不可）
  - 正規化した科目名（空白の畳み込みのみ）の**完全一致**
  - 出席確認の**受付時間と時限時間の一意な重なり**（複数時限に重なる連続時限などは不可）

  候補が複数ある場合・受付時間が無い場合は、直近・先頭の候補を選ばず**保留**します。
  ホーム用キャッシュは読み取り時刻・対象日付・端末ネットワーク種別と紐付け、メモリだけに保持します。
  **読み取りから 15 分**、日付の不一致、検出できたネットワーク断・種別の変更、
  プロセス終了で失効します。CLASS のログアウトや同じ通信種別内の回線切替は現在検出できないため、
  キャッシュは最新の認証・接続状態を保証しません。ホームには**「推定」と推定根拠**、読み取り時刻と**古さ**を表示します。
  学内/学外は**未確認**（CLASS 側のラベルを確認できていないため常に未確認）で、
  **実機 E2E は未検証**です。
  この処理は登録・送信・出席コードの入力も自動送信も行わず、読み取り専用です。
  確定済みの出席履歴はキャッシュ失効で削除されません。
- アプリ内ブラウザは大学・SSO の許可ホスト以外を WebView に読み込まず、外部ブラウザへ案内します
  （[`BrowserUrlPolicy.kt`](../app/src/main/java/org/cabetus/ui/browser/BrowserUrlPolicy.kt)）。
- CLASS のスマートフォン表示で `#menuPanel` の高さが 0 になる実機不具合に対し、
  `class.admin.tus.ac.jp`（完全一致ホスト）のページだけを対象に、`#menuPanel` の高さを
  viewport に合わせる最小限の DOM 補正を行います
  （[`ClassMenuPanelFix.kt`](../app/src/main/java/org/cabetus/ui/browser/ClassMenuPanelFix.kt)）。
  これは表示の補正のみで、入力内容の取得や外部送信は行いません。

つまり cabetus が大学の認証情報に触れる箇所は **LETUS / CLASS の WebView ログインのみ**で、
いずれもパスワードは受け取りません。

---

## 3. 通信先（送信先ホスト）の一覧

アプリがネットワーク通信を行う先は以下がすべてです（ソース内の URL 定数から機械的に列挙可能）。

| 送信先 | 用途 | 認証情報 | 備考 |
|---|---|---|---|
| `letus.ed.tus.ac.jp` および大学 SSO | LETUS の課題取得 | あなたのセッション Cookie | 大学公式サーバー |
| `class.admin.tus.ac.jp` および大学 SSO | CLASS の閲覧（出席チェック等） | CLASS のセッション Cookie | アプリ内ブラウザで開く。時間割読取後は保存前に確認（PDF取込は廃止） |
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

CLASS 出欠詳細の読み取りプレビューは画面表示のみで、Room には保存されません。
CLASS モバイル出席登録のホーム表示はプロセス内メモリに限られます。別途、授業を一意に特定できた確定出席は、科目コード・日付・時限・確認時刻・推定の有無と根拠を Room に保存します。受付中は履歴に保存しません。確認済み履歴はキャッシュの消去や読み取りの失敗では消えず、アプリのデータを消去するかアンインストールすると削除されます。

設定の「デバッグ」からオンにした場合に限り、CLASS 出席読取の件数・結果・エラー種別を端末ログに記録します。初期値はオフで、認証情報・認証コード・ページ内容は記録しません。

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
- CLASS の出欠詳細取り込みは第一段階として**読み取りプレビュー専用**です。画面上の1科目を読むだけで、
  DB 保存・全科目の自動巡回・定期同期は行いません。実機の実施日が年なしの MM/DD 表記であること、
  画面上の科目コードを機械的に確定できないことから、推測で補完せず未確定のまま表示します。
- 出欠一覧の自動巡回は**未完成・実機未検証**です。既定では `ClassAttendanceTourFeature.ENABLED = false`
  により開始 UI を出さず、巡回処理も生成しません。この無効化は一時的な安全措置であり、
  実機検証が完了するまで解除しません。
- モバイル出席は自動で読み取りますが、登録・コード入力・送信は行いません。ホームの一時結果は15分で失効し、対象を一意に特定した確定出席だけを履歴に保存します。紐付けが曖昧な場合は保留します。CLASSの確定出席表示からの実環境E2E確認は未完了です。

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
