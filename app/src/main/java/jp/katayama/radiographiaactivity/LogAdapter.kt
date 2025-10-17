package jp.katayama.radiographiaactivity

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// ViewHolder: item_log.xml内のビューへの参照を保持するクラス
class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    // item_log.xml内のTextViewを取得
    val logMessage: TextView = itemView.findViewById(R.id.log_message_text)

    // データをViewHolder内のビューにバインド（結びつける）ための関数
    fun bind(message: String) {
        logMessage.text = message
    }
}

class LogAdapter(private val logList: MutableList<String>) :
    RecyclerView.Adapter<LogViewHolder>() {

    // 1. ViewHolderを作成する（レイアウトのインフレート）
    // アダプターが新しい行のビューが必要なときに呼ばれる
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        // item_log.xml をViewオブジェクトに変換（インフレート）する
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    // 2. ViewHolderにデータを結びつける（バインド）
    // リストの行が表示されるときに呼ばれる
    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val logMessage = logList[position]
        holder.bind(logMessage)
    }

    // 3. データソースのアイテム数を返す
    // RecyclerViewがリストの長さを知るために必要
    override fun getItemCount(): Int {
        return logList.size
    }

    // 💡 ListViewで使用していたlogList.add(0, logMessage)と同様の処理を
    //    このアダプター内に追加するとよりクリーンです。
    fun addLog(logMessage: String) {
        logList.add(0, logMessage) // リストの先頭に追加
        notifyItemInserted(0)      // インデックス0にアイテムが挿入されたことを通知
    }
}

