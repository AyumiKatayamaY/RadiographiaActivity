package jp.katayama.radiographiaactivity

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import jp.katayama.radiographiaactivity.databinding.FragmentFirstBinding
import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.content.ServiceConnection
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import android.widget.Button
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.Date
import java.util.Locale

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {
    /*****************************************************
     * メンバ変数
     ****************************************************/
    //観測中の状態
    private var isMonitoring: Boolean = false
    //カメラ起動完了
    private var cameraStarted: Boolean = false

    private var _binding: FragmentFirstBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    // カメラ関連
    private var cameraService: CameraService? = null
    private var isBound = false

    //画像ファイルのログ
    //画面に表示するログのリスト、それを管理するアダプター、ログを記憶するファイル
    private val detectionLogList = mutableListOf<String>()
    private lateinit var logAdapter: LogAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var logFile: File

    // ログ書き込み専用のシングルトレッドディスパッチャを作成
    private val logDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /*****************************************************
     * FirstFragmentのイベント
     ****************************************************/
    // Fragmentが作成されるとき
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d("FirstFragment", "onCreateView")

        // ビューと結び付ける
        //「FragmentFirstBinding」は「fragment_first.xml」に結び付けられて自動生成されたクラス
        //「fragment_first.xml」を使用する場合は、クラス「FragmentFirstBinding」からinflateする。
        _binding = FragmentFirstBinding.inflate(inflater, container, false)

        return binding.root
    }

    // 画面が表示されるとき
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.d("FirstFragment", "onViewCreated")
        super.onViewCreated(view, savedInstanceState)

        // バージョン番号を表示
        val versionName = getVersionName()
        binding.textViewVersion.text = "Version: $versionName"

        // RecyclerViewの準備
        recyclerView = view.findViewById(R.id.detection_log_recyclerview)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        logAdapter = LogAdapter(detectionLogList)
        recyclerView.adapter = logAdapter

        //ログファイルの準備
        logFile = getLogFile(requireContext(), "detection_log.txt")

        //ログファイルをリストア
        restoreLogs();

        //観測開始ボタンの準備
        binding.buttonStartMonitoring.setOnClickListener {
            startMonitoring()
        }

        //観測終了ボタンの準備
        binding.buttonStopMonitoring.setOnClickListener {
            stopMonitoring()
        }

        //ログクリアボタンの準備
        binding.buttonClearLog.setOnClickListener {
            clearAllLogs()
        }

        //ログコピーボタンの準備
        binding.buttonCopyLog.setOnClickListener {
            copyLogToClipboard()
        }

        // FragmentのViewのライフサイクルを直接監視する
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Viewが再開されたとき (onResumeとほぼ同じタイミングだが、よりUIに近い)
            override fun onResume(owner: LifecycleOwner) {
                Log.d("FirstFragment", "viewLifecycleOwner: ON_RESUME")
                attachPreviewIfReady()
            }

            // Viewが停止したとき (onPauseとほぼ同じタイミングだが、よりUIに近い)
            override fun onPause(owner: LifecycleOwner) {
                Log.d("FirstFragment", "viewLifecycleOwner: ON_PAUSE")
                detachPreviewIfReady()
            }
        })
    }

    // 画面が表示されて操作可能になるとき（フォアグラウンドへ、もしくはアプリケーション起動時）
    override fun onResume() {
        Log.d("FirstFragment", "onResume")
        super.onResume()
        updateUI()
    }

    // 画面が見えなくなるとき（バックグラウンドへ、もしくはアプリケーション終了）
    override fun onStop() {
        Log.d("FirstFragment", "onStop")
        super.onStop()
        // アプリが不要になったらバインドを解除する
        if (isBound) { // ★★★ isBound をチェック ★★★
            requireActivity().unbindService(connection)
            isBound = false
        }
    }

    // Fragmentが破棄されるとき（アプリケーションが終了するとき）
    override fun onDestroyView() {
        Log.d("FirstFragment", "onDestroyView")

        // ログ書き込み専用のシングルトレッドディスパッチャをクローズ
        //ファイルアクセス中に実行して場合ファイルの健全性は担保されないが、そこまでは面倒みない。
        logDispatcher.close()

        super.onDestroyView()
        _binding = null
    }

    /*****************************************************
     * 観測処理関連
     ****************************************************/
    //観測開始
    private fun startMonitoring() {
        Log.d("FirstFragment", "startMonitoring")
        //既に観測中のときは何もしない。
        if (isMonitoring == true) {
            return
        }

        //通知が許可されていないときは解析を開始しない。
        if (notificationPermissionGranted() == false) {
            return
        }

        //カメラが許可されていないときは解析を開始しない。
        if (cameraPermissionGranted() == false) {
            return
        }

        //観測中
        isMonitoring = true

        //カメラ開始
        startCamera()

        //観測を開始する
        cameraService?.startMonitoring()
    }

    //観測終了
    private fun stopMonitoring() {
        Log.d("FirstFragment", "stopMonitoring")
        //観測停止中
        isMonitoring = false

        //観測を停止する
        cameraService?.stopMonitoring()
    }

    /*****************************************************
     * Camera関連
     ****************************************************/
    //CameraServiceとのコネクション
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("FirstFragment", "onServiceConnected")
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            isBound = true
            // Serviceに接続できたら、リスナー（具体的な処理）をセットする
            cameraService?.setLuminosityListener { message, logMessage ->
                // MyAnalyzerからメッセージが届いたら、UIスレッドで画面を更新する
                activity?.runOnUiThread {
                    updateUI(message, logMessage)
                }
            }
            //観測状態を更新する
            if (isMonitoring) {
                cameraService?.startMonitoring()
            } else {
                cameraService?.stopMonitoring()
            }
            // ★★★ サービスに接続できたら、すぐにプレビューをアタッチする ★★★
            attachPreviewIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("FirstFragment", "onServiceDisconnected")
            isBound = false
            cameraService = null
        }
    }

    //カメラ開始
    private fun startCamera() {
        Log.d("FirstFragment", "startCamera - cameraStarted=${cameraStarted}")
        //既に開始済みのときは何もしない。
        if (cameraStarted) {
            return
        }
        cameraStarted = true

        val intent = Intent(requireContext(), CameraService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun attachPreviewIfReady() {
        Log.d("FirstFragment", "attachPreviewIfReady")
        if (isBound && cameraService != null) {
            Log.d("FirstFragment", "Attaching preview...")
            cameraService?.attachPreview(binding.previewView.surfaceProvider)
        }
    }

    private fun detachPreviewIfReady() {
        Log.d("FirstFragment", "detachPreviewIfReady")
        if (isBound && cameraService != null) {
            Log.d("FirstFragment", "Detaching preview...")
            cameraService?.detachPreview()
        }
    }

    /*****************************************************
     * ユーザへの許可関連
     ****************************************************/
    //通知の許可を確認する
    private fun notificationPermissionGranted(): Boolean {
        Log.d("FirstFragment", "notificationPermissionGranted")
        // API 33 (Android 13) 以降の通知パーミッションの実行時要求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            //通知が許可されているかを確認する
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                //まだ許可されていない場合許可を求める
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }

        return true
    }

    //カメラの許可を確認する
    private fun cameraPermissionGranted(): Boolean {
        Log.d("FirstFragment", "cameraPermissionGranteds")
        //カメラの使用が許可されているかを確認する
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            //まだ許可されていない場合許可を求める
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            return false
        }

        return true
    }

    //通知の許可を求めるランチャー
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission())
        { isGranted: Boolean ->
            Log.d("FirstFragment", "requestNotificationPermission callback")
            if (!isGranted) {
                //許可されなかったとき
                Toast.makeText(requireContext(), "通知が許可されなかったため、観測を開始できません。", Toast.LENGTH_LONG).show()
            } else {
                //許可されたとき
                startMonitoring()
            }
        }

    //カメラを使用する許可を求めるランチャー
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission())
        { isGranted: Boolean ->
            Log.d("FirstFragment", "requestPermissionLauncher callback")
            if (!isGranted) {
                //許可されなかったとき
                Toast.makeText(requireContext(), "カメラが許可されなかったため、観測を開始できません。", Toast.LENGTH_SHORT).show()
            } else {
                //許可されたとき
                startMonitoring()
            }
        }

    fun getVersionName(): String {
        Log.d("FirstFragment", "getVersionName")
        try {
            // requireContext() で自分自身のContextを取得
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            return packageInfo.versionName ?: "N/A"
        } catch (e: Exception) {
            // 例外が発生した場合（まずあり得ないが念のため）
            e.printStackTrace()
            return "N/A" // "Not Available" の略
        }
    }

    /*****************************************************
     * UI関連
     ****************************************************/
    fun updateUI(message: String = "", logMessage: String = "") {
        Log.d("FirstFragment", "updateUI - message=${message}, logMessage~${logMessage}")
        var stringMonitoring = "観測停止"
        if (cameraService?.isMonitoring() == true) {
            stringMonitoring = "観測中"
        }

        val dateStr = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(System.currentTimeMillis()))
        binding.textviewMonitoring.text = "${dateStr} 現在の状況: ${stringMonitoring}"

        if (message.isNotEmpty()) {
            binding.textviewFirst.text = message
        }

        if (logMessage.isNotEmpty()) {
            updateLog(logMessage);
        }
    }

    // ログを更新する。
    fun updateLog(message: String) {
        Log.d("FirstFragment", "updateLog - message=${message}")
        // Adapter内のヘルパー関数を使用してデータとUIを更新
        logAdapter.addLog(message)

        // 最新のログ（インデックス0）にスクロール
        recyclerView.scrollToPosition(0)

        // ファイルへの書き込みを非同期で実行
        lifecycleScope.launch {
            appendLogToFile(logFile, message)
        }
    }

    /*****************************************************
     * ログファイル関連
     ****************************************************/
    //ログファイルを取得する
    fun getLogFile(context: Context, fileName: String): File {
        Log.d("FirstFragment", "getLogFile - fileName=${fileName}")
        // アプリの内部ストレージ（アンインストールで削除される）にある filesDir を使う
        val logDir = context.filesDir
        return File(logDir, fileName)
    }

    // ログファイルへ追記する。
    suspend fun appendLogToFile(logFile: File, logMessage: String) {
        Log.d("FirstFragment", "appendLogToFile - logFile=${logFile}, logMessage=${logMessage}")
        //バックグラウンドスレッド (logDispatcher) で実行
        //「logFile.appendText」に時間がかかってもメインスレッドの他のイベントは実行可能
        //但し、このブロックは「logFile.appendText」が完了するまで抜けない。
        //「appendLogToFile」から抜ける前に再度呼び出された場合、
        //「logDispatcher」によりSingleThreadExecutorを利用しているので
        //１つのスレッドで順に処理されることになる。
        withContext(logDispatcher) {
            try {
                // Kotlinの拡張関数 appendText() を使用し、既存のファイルに追記
                logFile.appendText("$logMessage\n")

            } catch (e: IOException) {
                // ファイル操作のエラーを処理 (ログ出力など)
                e.printStackTrace()
                // Log.e("LogWriter", "ファイルへの書き込みエラー: ${e.message}")
            }
        }
    }

    //ログファイルの内容を読み込み、行ごとに文字列のリストとして返す。
    suspend fun loadLogsFromFile(logFile: File): List<String> = withContext(logDispatcher) {
        Log.d("FirstFragment", "loadLogsFromFile - logFile=${logFile}")
        try {
            // Kotlinの拡張関数 readLines() を使用して、ファイル全体を行ごとにリストとして読み込む
            return@withContext logFile.readLines()
        } catch (e: IOException) {
            // ファイルが見つからない、または読み取りエラーが発生した場合
            e.printStackTrace()
            // 空のリストを返す
            return@withContext emptyList()
        }
    }

    //ログファイルをリストア
    fun restoreLogs() {
        Log.d("FirstFragment", "restoreLogs")
        // UIスレッドでコルーチンを開始
        lifecycleScope.launch {
            // 1. ファイルからログデータをバックグラウンドで読み込む
            val loadedLogs = loadLogsFromFile(logFile)

            // 2. 読み込みが完了したらUIスレッドに戻る (launchスコープで自動)

            // 3. データソースを更新
            detectionLogList.clear() // 既存のデータをクリア

            // 4. リストを逆順にして追加
            // ファイルの末尾（最新ログ）をリストの先頭に持ってくる
            detectionLogList.addAll(loadedLogs.reversed())

            // 5. RecyclerView全体を更新
            logAdapter.notifyDataSetChanged()

            // 6. (オプション) リストの先頭（最新ログ）にスクロール
            if (detectionLogList.isNotEmpty()) {
                recyclerView.scrollToPosition(0)
            }
        }
    }

    //ログファイルの中身をクリアする
    suspend fun clearLogFile(logFile: File) = withContext(logDispatcher) {
        Log.d("FirstFragment", "clearLogFile")
        try {
            // Kotlinの拡張関数 writeText() を使用。
            // 引数に空文字列 "" を渡すことで、既存の内容をすべて消去し、ファイルを空にします。
            logFile.writeText("")

        } catch (e: IOException) {
            // ファイル操作のエラーを処理
            e.printStackTrace()
        }
    }

    //画面とログファイルの両方をクリアする
    fun clearAllLogs() {
        Log.d("FirstFragment", "clearAllLogs")
        // 1. 画面（RecyclerView）をクリア
        detectionLogList.clear()
        logAdapter.notifyDataSetChanged()

        // 2. ログファイルのクリアを非同期で実行
        // lifecycleScopeを使用して、コンポーネントのライフサイクルに紐付けてコルーチンを起動
        lifecycleScope.launch {
            clearLogFile(logFile)
        }

        Toast.makeText(context, "ログがクリアされました。", Toast.LENGTH_SHORT).show()
    }

    //ログをクリップボードにコピーする
    private fun copyLogToClipboard() {
        Log.d("FirstFragment", "copyLogToClipboard")
        val context = context ?: return

        //データソースを参照
        val logEntries: List<String> = detectionLogList

        //全ログエントリーを改行で結合し、一つの文字列にする
        val combinedLogText = logEntries.joinToString(separator = "\n")

        //ログがないとき
        if (combinedLogText.isEmpty()) {
            Toast.makeText(context, "コピーするログがありません", Toast.LENGTH_SHORT).show()
            return
        }

        //クリップボードへの格納
        val clipboard: ClipboardManager? = ContextCompat.getSystemService(context, ClipboardManager::class.java)

        //クリップボードが取得できたかどうか確認する。
        if (clipboard != null) {
            //クリップボードが取得できた
            val clip = ClipData.newPlainText("Detection Log", combinedLogText)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(context, "ログ ${logEntries.size} 件をコピーしました", Toast.LENGTH_LONG).show()
        } else {
            //クリップボードが取得できなかった
            Toast.makeText(context, "クリップボードサービスにアクセスできません", Toast.LENGTH_SHORT).show()
        }
    }
}
