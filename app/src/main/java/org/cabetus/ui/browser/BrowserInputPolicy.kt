package org.cabetus.ui.browser

/**
 * 出欠一覧の巡回中に、画面とWebViewへのユーザー入力をどこまで止めるかを決める純ロジック。
 *
 * 巡回は「一覧リンクのクリック → 詳細の読み取り → 戻る」を自動で行うため、
 * 途中でユーザーが画面をタップ・スクロール・キーボード入力すると、
 * 別の科目を開いたり別画面へ遷移したりして、読み取り対象と表示がずれる。
 * そのため巡回の開始から終了（完了・停止・中止の確定）まで、
 * WebView のタッチ・キーボード・戻る操作をすべて遮断する。
 *
 * 中止だけはユーザーがいつでも行える必要があるため、画面側の
 * 「巡回を中止」ボタン（WebView の外）は有効のままにする。
 * Android に依存しないため JVM でテストできる。
 */
object BrowserInputPolicy {

    /**
     * WebView へのタッチ・スクロール・キーボード入力を遮断するか。
     * 巡回中（一覧 → 詳細への遷移中を含む）は開始から終了まで常に true。
     */
    fun blocksWebViewInput(tourRunning: Boolean): Boolean = tourRunning

    /**
     * 端末の戻る操作（BackHandler）を遮断するか。
     * 巡回中に画面を離れると WebView が破棄され、途中結果しか残らないため止める。
     * 戻る操作は WebView の履歴を戻すだけでなく、履歴が無ければ画面を閉じるため、
     * 遮断中は BackHandler 自体を有効にして（イベントを消費して）閉じないようにする。
     */
    fun blocksBackNavigation(tourRunning: Boolean): Boolean = tourRunning

    /**
     * 画面上部の「閉じる」ボタンを押せるか。
     * 巡回中に画面を閉じると WebView が破棄され、途中結果しか残らないため無効にする。
     */
    fun closeEnabled(tourRunning: Boolean): Boolean = !tourRunning

    /**
     * 「巡回を中止」ボタンを押せるか。
     * 巡回中でも中止は常に受け付ける。中止処理中（二重送信防止）だけ無効にする。
     */
    fun cancelEnabled(tourRunning: Boolean, cancelling: Boolean): Boolean =
        tourRunning && !cancelling
}
