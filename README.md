# RadiographiaActivity

[![Language](https://img.shields.io/badge/language-Kotlin-orange.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

`RadiographiaActivity` は、Androidデバイスのカメラを使用してリアルタイムでフレームを取得し、輝度解析を行うためのサンプルアプリケーションです。

このプロジェクトは、カメラからの映像を解析して輝度分布や特定の輝点（高輝度ピクセル）を検出する機能の実装例を示します。

## 主な機能

*   **リアルタイム輝度解析**: カメラプレビューからフレームを取得し、平均輝度、最大/最小輝度などを計算します。
*   **輝度分布のカウント**: 特定の輝度範囲に属するピクセル数をカウントし、分布を把握します。
*   **高輝度ピクセルの検出**: 設定されたしきい値を超える輝度を持つピクセルの座標と輝度値をリストとして保持します。
*   **画像保存**: 解析結果に基づいて、特定のフレームを画像ファイルとしてデバイスに保存する機能が含まれています。

## データ構造

このアプリでは、解析結果を管理するために以下のデータクラスを使用しています。

*   `AnalysisResult`: 一つのフレームに対する網羅的な解析結果を保持します。
    *   `averageLuminosity`: 平均輝度
    *   `maxLuminosity`: 最大輝度
    *   `brightPixels`: 閾値を超えた輝点のリスト
    *   その他、輝度分布カウントなど
*   `BrightPixel`: 高輝度ピクセルの情報を保持します。
    *   `x`, `y`: ピクセルの座標
    *   `luminosity`: ピクセルの輝度値

## セットアップとビルド方法

### 必要なもの

*   Android Studio Giraffe | 2022.3.1 以降
*   Kotlin プラグイン
*   Android SDK

### ビルド手順

1.  このリポジトリをクローンまたはダウンロードします。
    