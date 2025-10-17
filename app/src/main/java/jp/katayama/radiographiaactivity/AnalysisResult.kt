package jp.katayama.radiographiaactivity

import android.R
import kotlin.collections.mutableListOf

// 輝点情報を格納するためのデータクラス
data class BrightPixel(
    val x: Int,
    val y: Int,
    val luminosity: Int
)

//カメラから受け取ったフレームの解析結果
//「MyImageAnalyzer」が出力する情報
data class AnalysisResult(
    var blackOut: Boolean,          // 遮光中
    var averageLuminosity: Double,  // 平均輝度
    var minLuminosity: Int,         // 最小輝度
    var maxLuminosity: Int,         // 最大輝度
    var countPixel: Int,            // フレームのピクセル数
    var count_10_20: Int,           // 輝度10-20のピクセル数
    var count_20_30: Int,           // 輝度20-30のピクセル数
    var count_30_40: Int,           // 輝度30-40のピクセル数
    var count_40_50: Int,           // 輝度40-50のピクセル数
    var count_50_100: Int,          // 輝度50-100のピクセル数
    var count_100_150: Int,         // 輝度100-1500のピクセル数
    var count_150_plus: Int,        // 輝度150以上のピクセル数
    var savedImage: Boolean,        // イメージファイルに保存済
    var fileName: String,           // イメージファイルのファイル名
    var brightPixels : MutableList<BrightPixel> = mutableListOf() // 一定の輝度以上のピクセルに関する情報
)
