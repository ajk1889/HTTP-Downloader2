package resonance.http.httpdownloader.helpers.db

import androidx.room.*
import resonance.http.httpdownloader.core.now
import java.text.SimpleDateFormat
import java.util.*

@Entity
data class SavedPageItem(
    @ColumnInfo(name = "title") var title: String,
    @ColumnInfo(name = "path") var path: String,
    @ColumnInfo(name = "time") var time: Long = now(),
    @ColumnInfo(name = "ico") var ico: String? = null
) {
    @ColumnInfo(name = "id")
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L

    /**to be used in listAdapter*/
    @Ignore
    var isSelected: Boolean = false
    @Ignore
    var createdTime = now()

    /**for debugging purposes*/
    override fun toString(): String {
        val time = SimpleDateFormat.getDateTimeInstance().format(Date(time))
        return "$title ($path) : $time"
    }
}

@Dao
interface SavedPagesDbConn {
    @Query("SELECT * FROM SavedPageItem ORDER BY id DESC LIMIT :from, :count")
    fun getAll(from: Int = 0, count: Int = 50): List<SavedPageItem>

    @Query("SELECT * FROM SavedPageItem WHERE title LIKE :text ORDER BY id DESC")
    fun search(text: String): List<SavedPageItem>

    @Query("SELECT * FROM SavedPageItem WHERE id=:id")
    fun findItemById(id: Long): SavedPageItem

    @Query("DELETE FROM SavedPageItem")
    fun clearAll()

    @Query("DELETE FROM SavedPageItem WHERE id IN (:items)")
    fun removeAll(items: Array<Long>)

    @Insert
    fun insert(savedPageItem: SavedPageItem): Long

    @Delete
    fun delete(savedPageItem: SavedPageItem)
}

@Database(entities = [SavedPageItem::class], version = 1, exportSchema = false)
abstract class SavedPagesDB : RoomDatabase() {
    abstract fun conn(): SavedPagesDbConn
}