package resonance.http.httpdownloader.helpers.db

import androidx.room.*
import resonance.http.httpdownloader.core.now

@Entity
data class LogItem(
    @ColumnInfo(name = "tag") var tag: String,
    @ColumnInfo(name = "msg") var msg: String,
    @ColumnInfo(name = "time") var time: Long = now()
) {
    @ColumnInfo(name = "id")
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L

    override fun toString(): String {
        return "$time   $tag: $msg"
    }
}

@Dao
interface LogDbConn {
    @Query("SELECT * FROM logitem ORDER BY id DESC LIMIT :count")
    fun getAll(count: Int = 500): List<LogItem>

    @Insert
    fun insert(item: LogItem)

    @Query("DELETE FROM logitem WHERE time < :timeToBeKept")
    fun clearOlderThan(timeToBeKept: Long): Int
}

@Database(entities = [LogItem::class], version = 1, exportSchema = false)
abstract class LogDB : RoomDatabase() {
    abstract fun conn(): LogDbConn
}