package jp.katayama.radiographiaactivity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.icu.text.SimpleDateFormat
import android.os.Build
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Binder
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleService
import com.google.common.util.concurrent.ListenableFuture
import java.util.Date
import java.util.Locale

class CameraService : LifecycleService() {
    /*****************************************************
     * 定数
     ****************************************************/
    companion object {
        private const val NOTIFICATION_ID = 1
    }

    /*****************************************************
     * メンバ変数
     ****************************************************/
    //観測中の状態
    private var isMonitoring: Boolean = false
    //MyImageAnalyzerのインスタンスを保持するためのプロパティ
    private var myImageAnalyzerInstance: MyImageAnalyzer? = null

    //MyImageAnalyzerを実行するためのシングルスレッド
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    //カメラのフレームを解析する。これに「MyImageAnalyzer」を設定しておく。
    private var imageAnalyzer: ImageAnalysis? = null
    //カメラのフレームをプレビューで表示する。
    private lateinit var preview: Preview

    //UIへのリスナー
    //これはメインスレッドで呼び出さなければならない。
    private var luminosityListener: ((String, String) -> Unit)? = null

    //カメラから受け取ったフレーム情報の統計
    //スレッド「cameraExecutor」で更新する。
    private var blackOut: Boolean = false
    private var averageLuminosity: Double = 0.0
    private var minLuminosity: Int = 256
    private var maxLuminosity: Int = 0
    private var countPixel: Int = 0
    private var countUi: Long = 0
    private var total_count_10_20: Long = 0
    private var total_count_20_30: Long = 0
    private var total_count_30_40: Long = 0
    private var total_count_40_50: Long = 0
    private var total_count_50_100: Long = 0
    private var total_count_100_150: Long = 0
    private var total_count_150_plus: Long = 0

    /*****************************************************
     * 公開関数
     ****************************************************/
    //カメラのプレビュー表示を開始する
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        Log.d("CameraService", "attachPreview")
        // メインスレッドで実行することを保証する
        ContextCompat.getMainExecutor(this).execute {
            preview.setSurfaceProvider(surfaceProvider)
        }
    }

    //カメラのプレビュー表示を停止する
    fun detachPreview() {
        Log.d("CameraService", "detachPreview")
        ContextCompat.getMainExecutor(this).execute {
            preview.setSurfaceProvider(null)
        }
    }

    //UIへのリスナーを設定する
    fun setLuminosityListener(listener: (String, String) -> Unit) {
        Log.d("CameraService", "setLuminosityListener")
        this.luminosityListener = listener
        // リスナーが設定されたら、現在の累計値を一度送ってあげる
        updateUi()
    }

    //観測を開始する
    fun startMonitoring() {
        Log.d("CameraService", "startMonitoring - myImageAnalyzerInstance=${myImageAnalyzerInstance}")
        isMonitoring = true
        myImageAnalyzerInstance?.startMonitoring()
    }

    //観測を停止する
    fun stopMonitoring() {
        Log.d("CameraService", "stopMonitoring - myImageAnalyzerInstance=${myImageAnalyzerInstance}")
        isMonitoring = false
        myImageAnalyzerInstance?.stopMonitoring()
    }

    //観測中の状態を確認する
    fun isMonitoring(): Boolean {
        Log.d("CameraService", "monitoring - myImageAnalyzerInstance=${myImageAnalyzerInstance}")
        return (myImageAnalyzerInstance?.isMonitoring() == true)
    }

    /*****************************************************
     * 基本クラスのoverride
     ****************************************************/
    //サービスが初めて作成される時に一度だけ呼ばれる。
    //メインスレッドで実行される
    override fun onCreate() {
        Log.d("CameraService", "onCreate")
        super.onCreate()

        //シングルスレッドを作成
        cameraExecutor = Executors.newSingleThreadExecutor()

        // カメラの初期化
        initializeCamera()

        Log.d("CameraService", "onCreate: サービスが作成され、Executorが初期化されました。")
    }

    //サービスが破棄される時に呼ばれる。
    //メインスレッドで実行される
    override fun onDestroy() {
        Log.d("CameraService", "onDestroy")
        super.onDestroy()

        // CameraX関連のリソース解放（必要に応じて）
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            Log.e("CameraService", "onDestroy - Camera unbind failed", e)
        }

        // シングルスレッドのシャットダウン
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }

        Log.i("CameraService", "onDestroy - CameraService destroyed and resources released")
    }

    //startServiceメソッドやstartForegroundServiceメソッドが実行されたときに呼び出される。
    //メインスレッドで実行される
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CameraService", "onStartCommand")
        super.onStartCommand(intent, flags, startId)

        //通義チャネルを生成し、システムに登録する。
        createNotificationChannel()

        //このサービス（CameraService）をフォアグラウンドサービスで実行する。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            //新しいAPIではforegroundServiceTypeの指定が必要
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            //古いAPI
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        return START_STICKY
    }

    //bindServiceメソッドが実行されたときに呼び出される。
    //メインスレッドで実行される
    override fun onBind(intent: Intent): IBinder? {
        Log.d("CameraService", "onBind")
        super.onBind(intent)
        return binder
    }

    /*****************************************************
     * 通知関連
     ****************************************************/
    //通義チャネルを生成し、システムに登録する。
    //メインスレッドで実行される
    private fun createNotificationChannel() {
        Log.d("CameraService", "createNotificationChannel ${Build.VERSION.SDK_INT}")
        //現在の端末が動作している AndroidのAPIレベルが特定バージョン以降であれば
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("CameraService", "createNotificationChannel - Ver OK")
            val channel = NotificationChannel(
                "radiographia_camera_channel", // チャンネルID
                "カメラサービス",           // ユーザーに表示される名前
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "放射線痕跡検出のためのカメラサービス"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    //通知を作成し、チャネルIDを指定する
    //メインスレッドで実行される
    private fun buildNotification(): Notification {
        Log.d("CameraService", "buildNotification")
        val builder = NotificationCompat.Builder(this, "radiographia_camera_channel")
            .setContentTitle("RadiographiaActivety")
            .setContentText("放射線痕跡を検出しています")
            //.setSmallIcon(R.drawable.ic_camera) // 適切なアイコンを指定
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        Log.d("CameraService", "createNotificationChannel - builder: $builder builder.build(): ${builder.build()}")
        return builder.build()
    }

    /*****************************************************
     * カメラ関連
     ****************************************************/
    //カメラの初期化
    //メインスレッドで実行される
    private fun initializeCamera() {
        Log.d("CameraService", "initializeCamera")
        preview = Preview.Builder().build()

        // CameraXの初期化処理を開始して、その結果が将来的に得られることを表すListenableFutureを受け取る。
        // CameraXはカメラシステムへの接続や初期設定を非同期で開始する。
        // このメソッドはすぐに戻る。
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        //CameraXの初期化が完了したときに実行されるコールバック関数（listener）を登録する。
        //listenerはexecutorで実行される。
        //今回の場合「executor=ContextCompat.getMainExecutor(this)」であるため
        //「listener」をメインスレッドで実行するように指定している。
        //「getMainExecutor」は今どのスレッドで動作しているかにかかわらず、「アプリのUIスレッド」を取得する。
        //「addListener」の処理そのものはすぐに戻るが、listenerは非同期で処理される。
        cameraProviderFuture.addListener({
            // 初期化が完了したCameraXのCameraProviderを取り出す。
            val cameraProvider = cameraProviderFuture.get()
            Log.d("CameraService", "initializeCamera - cameraProviderFuture.get: $cameraProvider")

            //カメラをバインドする
            bindCameraIfReady(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    // 2つの非同期処理が両方完了したかチェックして、カメラをバインドするメソッド
    // メインスレッドで実行される
    private fun bindCameraIfReady(cameraProvider: ProcessCameraProvider) {
        Log.d("CameraService", "bindCameraIfReady")

        // カメラからの受け取ったフレームを「MyImageAnalyzer」を使用して分析する
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                Log.d("CameraService", "bindCameraIfReady - setAnalyzer: $cameraExecutor")

                //MyImageAnalyzerのインスタンスを生成する。
                val analyzer = MyImageAnalyzer(this)
                { result ->
                    analyzeImage(result);
                    updateUi(result)
                }

                //クラスのプロパティに保存する
                myImageAnalyzerInstance = analyzer

                //監視中の状態を設定する
                if (isMonitoring) {
                    analyzer.startMonitoring()
                }

                //analyzerをcameraExecutor（メインスレッドとは別のスレッド）で実行するように設定する。
                it.setAnalyzer(cameraExecutor, analyzer)
            }

        try {
            // ここで初めてbindToLifecycleを安全に呼び出せる
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,         //ライフサイクルとの紐づけ
                cameraSelector,             //使用するカメラ
                preview,         //実行する処理１（可変引数）
                imageAnalyzer               //実行する処理２（可変引数）
            )
            Log.d("CameraService", "bindCameraIfReady: Camera binding successful.")

        } catch (exc: Exception) {
            Log.e("CameraService", "bindCameraIfReady: Camera binding failed", exc)
        }
    }

    //カメラのイメージを解析する
    private fun analyzeImage(result :AnalysisResult? = null) {
        Log.d("CameraService", "analyzeImage: result=${result}")
        if (result != null) {
            blackOut = result.blackOut
            averageLuminosity = result.averageLuminosity
            if (result.maxLuminosity < minLuminosity) minLuminosity = result.maxLuminosity
            if (maxLuminosity < result.maxLuminosity) maxLuminosity = result.maxLuminosity
            countPixel = result.countPixel
            total_count_10_20 += result.count_10_20
            total_count_20_30 += result.count_20_30
            total_count_30_40 += result.count_30_40
            total_count_40_50 += result.count_40_50
            total_count_50_100 += result.count_50_100
            total_count_100_150 += result.count_100_150
            total_count_150_plus += result.count_150_plus
        }
    }

    /*****************************************************
     * UI関連
     ****************************************************/
    // UI更新
    // これは任意のスレッドで実行される。
    private fun updateUi(result :AnalysisResult? = null) {
        countUi++

        Log.d("CameraService", "updateUi - %d : 遮光中、測定中 (平均輝度: %.1f,最小輝度:%d,最大輝度:%d,ピクセル数:%d,10:%d,20:%d,30:%d,40:%d,50:%d,100:%d,150:%d)\n".format(
            countUi,
            averageLuminosity,
            minLuminosity,
            maxLuminosity,
            countPixel,
            total_count_10_20,
            total_count_20_30,
            total_count_30_40,
            total_count_40_50,
            total_count_50_100,
            total_count_100_150,
            total_count_150_plus))

        // UIスレッドで実行する必要があるため、Handlerを使う
        // 「post」以降のラムダ式がUIスレッドで実行される
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            if (blackOut) {
                //遮光されている時
                //現在の状態を表示
                val reportBuilder = StringBuilder()
                reportBuilder.append(String.format("輝度の平均-最小-最大: %.1f - %d - %d\n", averageLuminosity, minLuminosity, maxLuminosity))
                reportBuilder.append("ピクセル数: ${countPixel}\n")
                reportBuilder.append("累計カウント:\n")
                reportBuilder.append("10-20: ${total_count_10_20}点\n")
                reportBuilder.append("20-30: ${total_count_20_30}点\n")
                reportBuilder.append("30-40: ${total_count_30_40}点\n")
                reportBuilder.append("40-50: ${total_count_40_50}点\n")
                reportBuilder.append("50-100: ${total_count_50_100}点\n")
                reportBuilder.append("100-150: ${total_count_100_150}点\n")
                reportBuilder.append("150以上: ${total_count_150_plus}点")

                //画像ファイルを保存しているときは、ログのメッセージも生成する。
                var logMessage = ""
                if (result?.savedImage == true) {
                    val pixelString = result.brightPixels.joinToString(separator = ", ") { pixel ->
                        "${pixel.x}-${pixel.y}-${pixel.luminosity}"
                    }
                    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(System.currentTimeMillis()))
                    logMessage = "$dateStr - 最大輝度: ${result.maxLuminosity}, 輝点: ${pixelString}, ${result.fileName}"
                }

                //listenerを呼び出して画面に表示する。
                luminosityListener?.invoke(reportBuilder.toString(), logMessage)

            } else {
                //遮光されていない時
                //listenerを呼び出して画面に表示する。
                val reportBuilder = StringBuilder()
                reportBuilder.append(String.format("輝度の平均-最小-最大: %.1f - %d - %d\n", averageLuminosity, minLuminosity, maxLuminosity))
                reportBuilder.append("ピクセル数: ${countPixel}\n")
                reportBuilder.append("カメラを遮光してください\n")
                luminosityListener?.invoke(reportBuilder.toString(), "")
            }
        }
    }
}
