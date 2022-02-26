package resonance.http.httpdownloader.helpers.db

import androidx.room.*
import resonance.http.httpdownloader.core.now
import java.text.SimpleDateFormat
import java.util.*

@Entity
data class HistoryItem(
    @ColumnInfo(name = "title") var title: String,
    @ColumnInfo(name = "url") var url: String,
    @ColumnInfo(name = "time") var time: Long = now()
) {
    @ColumnInfo(name = "id")
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L
    @ColumnInfo(name = "ico")
    var ico: String? = null

    /**to be used in listAdapter*/
    @Ignore
    var isSelected: Boolean = false
    @Ignore
    var createdTime = now()

    /**for debugging purposes*/
    override fun toString(): String {
        val time = SimpleDateFormat.getDateTimeInstance().format(Date(time))
        return "$title ($url) : $time"
    }
}

@Dao
interface HistoryDbConn {
    @Query("SELECT * FROM historyitem ORDER BY id DESC LIMIT :from, :count")
    fun getAll(from: Int = 0, count: Int = 50): List<HistoryItem>

    @Query("SELECT * FROM historyitem WHERE title LIKE :text OR url LIKE :text ORDER BY id DESC")
    fun search(text: String): List<HistoryItem>

    @Query("SELECT DISTINCT url FROM historyitem WHERE url LIKE :text ORDER BY id DESC LIMIT 3")
    fun searchUrl(text: String): List<String>

    @Query("SELECT * FROM historyitem WHERE id=:id")
    fun findItemById(id: Long): HistoryItem

    @Query("DELETE FROM historyitem")
    fun clearAll()

    @Query("DELETE FROM historyitem WHERE id < (SELECT MAX(id) FROM historyitem) - :itemsToKeep")
    fun clearOld(itemsToKeep: Int)

    @Query("DELETE FROM historyitem WHERE id IN (:items)")
    fun removeAll(items: Array<Long>)

    @Query("UPDATE historyitem SET ico=:base64 WHERE id=:id")
    fun setIco(id: Long, base64: String)

    @Query("UPDATE historyitem SET ico=:base64 WHERE url=:url")
    fun setIco(url: String, base64: String)

    @Query("UPDATE historyitem SET title=:title WHERE id=:id")
    fun setTitle(id: Long, title: String)

    @Query("UPDATE historyitem SET title=:title WHERE url=:url")
    fun setTitle(url: String, title: String)

    @Insert
    fun insert(historyItem: HistoryItem): Long

    @Delete
    fun delete(historyItem: HistoryItem)
}

@Database(entities = [HistoryItem::class], version = 1, exportSchema = false)
abstract class HistoryDB : RoomDatabase() {
    abstract fun conn(): HistoryDbConn
}