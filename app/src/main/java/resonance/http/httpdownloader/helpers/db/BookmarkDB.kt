package resonance.http.httpdownloader.helpers.db

import androidx.room.*
import resonance.http.httpdownloader.core.now
import java.text.SimpleDateFormat
import java.util.*

@Entity
data class BookmarkItem(
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
interface BookmarkDbConn {
    @Query("SELECT * FROM bookmarkItem ORDER BY id DESC LIMIT :from, :count")
    fun getAll(from: Int = 0, count: Int = 50): List<BookmarkItem>

    @Query("SELECT * FROM bookmarkItem WHERE title LIKE :text OR url LIKE :text ORDER BY id DESC")
    fun search(text: String): List<BookmarkItem>

    @Query("SELECT * FROM bookmarkItem WHERE id=:id")
    fun findItemById(id: Long): BookmarkItem

    @Query("DELETE FROM bookmarkItem")
    fun clearAll()

    @Query("DELETE FROM bookmarkItem WHERE id IN (:items)")
    fun removeAll(items: Array<Long>)

    @Query("DELETE FROM bookmarkItem WHERE url=:url")
    fun remove(url: String)

    @Query("UPDATE bookmarkItem SET ico=:base64 WHERE id=:id")
    fun setIco(id: Long, base64: String)

    @Query("UPDATE bookmarkItem SET ico=:base64 WHERE url=:url")
    fun setIco(url: String, base64: String)

    @Query("UPDATE bookmarkItem SET title=:title WHERE id=:id")
    fun setTitle(id: Long, title: String)

    @Query("UPDATE bookmarkItem SET title=:title WHERE url=:url")
    fun setTitle(url: String, title: String)

    @Insert
    fun insert(bookmarkItem: BookmarkItem): Long

    @Delete
    fun delete(bookmarkItem: BookmarkItem)
}

@Database(entities = [BookmarkItem::class], version = 2, exportSchema = false)
abstract class BookmarkDB : RoomDatabase() {
    abstract fun conn(): BookmarkDbConn
}