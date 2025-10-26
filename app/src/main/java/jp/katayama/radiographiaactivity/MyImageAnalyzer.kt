package jp.katayama.radiographiaactivity

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream

class MyImageAnalyzer(private val context: Context, private val listener: (AnalysisResult) -> Unit) : ImageAnalysis.Analyzer {
    /*****************************************************
     * メンバ変数
     ****************************************************/
    /**
     * YUV画像データを保持するためのシンプルなデータクラス
     */
    private data class YuvFrame(
        val yBuffer: ByteBuffer,
        val uBuffer: ByteBuffer,
        val vBuffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val yRowStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val uPixelStride: Int,
        val vPixelStride: Int
    )

    // 輝点の累積
    private var cumulativeFrame: YuvFrame? = null

    //観測中の状態
    private var isMonitoring: Boolean = false

    //画像ファイル保存のクールダウン設定
    private val saveCooldownMs = 5000L
    private var lastSaveTime: Long = 0

    //UIへの通知のクールダウン設定
    private val listenerCooldownMs = 3000L
    private var lastToListener: Long = 0

    // 前回保存時の最大輝度
    private var lastSavedMaxLuminosity = 0

    /*****************************************************
     * 公開関数
     ****************************************************/
    //観測を開始する
    fun startMonitoring() {
        this.isMonitoring = true
        cumulativeFrame = null
    }

    //観測を停止する
    fun stopMonitoring() {
        // 累積用のImageProxyが保持されていれば、JPEGファイルとして保存する
        cumulativeFrame?.let { frame ->
            saveYuvFrameAsJpeg(frame)
        }

        this.isMonitoring = false
    }

    //観測中
    fun isMonitoring(): Boolean {
        return this.isMonitoring
    }

    @SuppressLint("UnsafeOptInUsageError")
    /*****************************************************
     * 基本クラスのoverride
     ****************************************************/
    override fun analyze(imageProxy: ImageProxy) {
        var shouldCloseImage = true

        try {
            //解析結果を初期化
            val brightPixels = mutableListOf<BrightPixel>()
            var result = AnalysisResult(
                blackOut = false,
                averageLuminosity = 0.0,
                minLuminosity = 255,
                maxLuminosity = 0,
                countPixel = 0,
                count_10_20 = 0,
                count_20_30 = 0,
                count_30_40 = 0,
                count_40_50 = 0,
                count_50_100 = 0,
                count_100_150 = 0,
                count_150_plus = 0,
                savedImage = false,
                fileName = "",
                brightPixels = brightPixels
            )

            //画像を解析
            analyzeImage(imageProxy, result)

            //輝点があるとき画像に保存する
            brightPixelsSaveImage(imageProxy, result)

            if (isMonitoring && result.brightPixels.isNotEmpty()) {
                if (cumulativeFrame == null) {
                    // 初回の輝点フレームを保持する
                    Log.d("MyImageAnalyzer", "最初の輝点を検出。このフレームを累積ベースとして保持します。")
                    cumulativeFrame = copyImageProxyToYuvFrame(imageProxy)

                } else {
                    // 2回目以降は、保持しているフレームのYUVバッファに輝度と色情報を上書きする
                    updateCumulativeFrame(result.brightPixels, imageProxy)
                }
            }

            //listenerに通知
            toListener(result)

        } finally {
            imageProxy.close()
        }
    }

    /*****************************************************
     * 非公開関数
     ****************************************************/
    //画像を解析
    private fun analyzeImage(imageProxy: ImageProxy, result: AnalysisResult) {
        val planes = imageProxy.planes
        val yBuffer: ByteBuffer = planes[0].buffer // 輝度情報(Y)のバッファ
        val width = imageProxy.width

        //ピクセルの位置(x,y座標を求めるため)
        var pixelIndex = 0

        //このフレームの輝度合計
        var frame_totalLuminosity: Long = 0

        //ピクセルを精査する
        while (yBuffer.hasRemaining()) {
            val luminosity = yBuffer.get().toInt() and 0xFF

            //輝度合計
            frame_totalLuminosity += luminosity

            //最小輝度、最大輝度
            if (luminosity < result.minLuminosity) result.minLuminosity = luminosity
            if (result.maxLuminosity < luminosity) result.maxLuminosity = luminosity

            //観測中のときだけ実施
            if (isMonitoring) {
                // 輝度毎のヒストグラム
                when {
                    luminosity >= 150 -> result.count_150_plus++
                    luminosity >= 100 -> result.count_100_150++
                    luminosity >= 50 -> result.count_50_100++
                    luminosity >= 40 -> result.count_40_50++
                    luminosity >= 30 -> result.count_30_40++
                    luminosity >= 20 -> result.count_20_30++
                    luminosity >= 10 -> result.count_10_20++
                }

                // 輝度条件を満たしたら、リストに追加する
                if (luminosity > 30) {
                    val x = pixelIndex % width
                    val y = pixelIndex / width

                    // BrightPixelオブジェクトを作成してリストに追加
                    result.brightPixels.add(BrightPixel(x, y, luminosity))
                }
            }

            pixelIndex++
        }

        // フレームのピクセル数
        result.countPixel = pixelIndex

        // 平均輝度を計算
        result.averageLuminosity = frame_totalLuminosity.toDouble() / pixelIndex

        // 遮光されているかどうかを判定
        result.blackOut = result.averageLuminosity < 20;

        //バッファを巻き戻す
        yBuffer.rewind()

        return
    }

    //ImageProxyの内容を新しいYuvFrameオブジェクトにコピーする
    private fun copyImageProxyToYuvFrame(imageProxy: ImageProxy): YuvFrame {
        Log.d("MyImageAnalyzer", "最初の輝点を検出。フレームデータをコピーして保持します。")
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        return YuvFrame(
            yBuffer = ByteBuffer.allocateDirect(yPlane.buffer.capacity()).apply { put(yPlane.buffer.asReadOnlyBuffer()) },
            uBuffer = ByteBuffer.allocateDirect(uPlane.buffer.capacity()).apply { put(uPlane.buffer.asReadOnlyBuffer()) },
            vBuffer = ByteBuffer.allocateDirect(vPlane.buffer.capacity()).apply { put(vPlane.buffer.asReadOnlyBuffer()) },
            width = imageProxy.width,
            height = imageProxy.height,
            yRowStride = yPlane.rowStride,
            uRowStride = uPlane.rowStride,
            vRowStride = vPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vPixelStride = vPlane.pixelStride
        )
    }

    //保持しているcumulativeFrameのYUVバッファに、新しい輝点情報を上書きする
    private fun updateCumulativeFrame(brightPixels: List<BrightPixel>, newImage: ImageProxy) {
        val frame = cumulativeFrame ?: return
        val newUBuffer = newImage.planes[1].buffer
        val newVBuffer = newImage.planes[2].buffer

        brightPixels.forEach { pixel ->
            val yIndex = pixel.y * frame.yRowStride + pixel.x
            val originalLuminosity = frame.yBuffer.get(yIndex).toInt() and 0xFF

            if (pixel.luminosity > originalLuminosity) {
                // Yプレーンの上書き
                frame.yBuffer.put(yIndex, pixel.luminosity.toByte())

                // U, Vプレーンの上書き
                val uvIndex = (pixel.y / 2) * frame.uRowStride + (pixel.x / 2) * frame.uPixelStride

                val oldUPos = newUBuffer.position()
                val oldVPos = newVBuffer.position()

                if (uvIndex < frame.uBuffer.capacity() && uvIndex < newUBuffer.capacity()) {
                    frame.uBuffer.put(uvIndex, newUBuffer.get(uvIndex))
                }
                if (uvIndex < frame.vBuffer.capacity() && uvIndex < newVBuffer.capacity()) {
                    frame.vBuffer.put(uvIndex, newVBuffer.get(uvIndex))
                }

                newUBuffer.position(oldUPos)
                newVBuffer.position(oldVPos)
            }
        }
    }

    //YuvFrameオブジェクトからJPEG画像を生成して保存する
    private fun saveYuvFrameAsJpeg(frame: YuvFrame) {
        // バッファのポジションを先頭に戻す
        frame.yBuffer.rewind()
        frame.uBuffer.rewind()
        frame.vBuffer.rewind()

        // YuvImageに変換するために、Y,U,Vを一つのByteArrayにまとめる
        val ySize = frame.yBuffer.remaining()
        val uSize = frame.uBuffer.remaining()
        val vSize = frame.vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        frame.yBuffer.get(nv21, 0, ySize)
        frame.vBuffer.get(nv21, ySize, vSize)
        frame.uBuffer.get(nv21, ySize + vSize, uSize)

        // YuvImageオブジェクトを生成
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, frame.width, frame.height, null)

        // ★★★ YuvImageをBitmapに変換する処理 ★★★
        val out = ByteArrayOutputStream()
        // まずYuvImageをJPEG形式でメモリ上のバイト配列に圧縮する
        yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), 100, out)

        // そのバイト配列をデコードしてBitmapオブジェクトを生成する
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // ファイル保存処理
        saveImage(bitmap, "Cumulative_") // ★ 生成したBitmapを渡す
    }

    //listenerへ通知
    private fun toListener(result: AnalysisResult) {
        // クールダウンが終わったかどうかを判定
        val currentTime = System.currentTimeMillis()
        val isCooldownOver = currentTime - lastToListener > listenerCooldownMs

        // クールダウンが終わったか、あるいは画像ファイルを保存したとき
        if (isCooldownOver || result.savedImage) {
            //listenerへ通知
            listener(result)

            //通知した時間を記憶
            lastToListener = currentTime
        }
    }

    //輝点があるとき画像に保存する
    private fun brightPixelsSaveImage(imageProxy: ImageProxy, result: AnalysisResult) {
        //遮光されていなければ何もしない。
        if (!result.blackOut) {
            return
        }

        //輝点がなければ何もしない。
        if (result.brightPixels.isEmpty()) {
            return
        }

        // クールダウンが終わったかどうかを判定
        val currentTime = System.currentTimeMillis()
        val isCooldownOver = currentTime - lastSaveTime > saveCooldownMs

        // 前回保存時より明るいかどうかを判定
        val isBrighterThanLast = result.maxLuminosity > lastSavedMaxLuminosity
        if (isBrighterThanLast) {
            lastSavedMaxLuminosity = result.maxLuminosity
        }

        // 「クールダウンが終わった」または「クールダウン中だが前回より明るい」場合
        if (isCooldownOver || isBrighterThanLast) {
            //画像を保存する
            result.fileName = saveImage(imageProxy.toBitmap(), "Radiographia_")
            result.savedImage = true

            //保存した時間を記憶
            lastSaveTime = currentTime
        }

        return
    }

    //Bitmap画像をストレージに保存する
    private fun saveImage(bitmap: Bitmap, prefix: String): String {
        // ファイル名に現在の日時を入れる
        val fileName = "${prefix}${System.currentTimeMillis()}.jpg"

        // 保存先の情報を作成
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            // Android Q (API 29)以降は、保存先のフォルダを細かく指定できる
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Radiographia")
            }
        }

        // コンテンツリゾルバを使って画像を保存
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            try {
                val outputStream = resolver.openOutputStream(it)
                outputStream?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                }
                Log.d("MyImageAnalyzer", "画像を保存しました: $uri")
            } catch (e: Exception) {
                Log.e("MyImageAnalyzer", "画像の保存に失敗しました。", e)
            }
        }

        return fileName;
    }
}
