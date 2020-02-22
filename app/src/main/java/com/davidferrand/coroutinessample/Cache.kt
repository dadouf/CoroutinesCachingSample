package com.davidferrand.coroutinessample

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class Cache(
    private val tag: String,
    val readDelayMs: Long,
    val writeDelayMs: Long
//    onDataChangedObserver: (property: KProperty<*>, oldValue: Data?, newValue: Data?) -> Unit,
//    onActiveJobCountChangedObserver: (property: KProperty<*>, oldValue: Int, newValue: Int) -> Unit
) {

    var activeJobCount: Int = 0
        /*by Delegates.observable(
                0,
                onActiveJobCountChangedObserver
            )*/
        private set

    suspend fun read() = withContext(Dispatchers.IO) {
        log("long $tag.read() operation") {
            activeJobCount++
            try {
                Thread.sleep(readDelayMs)
                actuallyReadData()
            } finally {
                activeJobCount--
            }
        }
    }

    suspend fun write(data: Data) = withContext(Dispatchers.IO) {
        log("long $tag.write() operation") {
            activeJobCount++
            try {
                Thread.sleep(writeDelayMs)
                actuallyWriteData(data)
            } finally {
                activeJobCount--
            }
        }
    }

    // We add some artificial delays to read and write, once these are expired,
    // we want to actually read/write

    abstract fun actuallyReadData(): Data?
    abstract fun actuallyWriteData(data: Data)
}

class RamCache : Cache("ramCache", readDelayMs = 10, writeDelayMs = 20) {
    private var cachedData: Data? = null

    override fun actuallyReadData(): Data? {
        return cachedData
    }

    override fun actuallyWriteData(data: Data) {
        cachedData = data
    }
}

class DiskCache(val dao: DataDao) : Cache("diskCache", readDelayMs = 500, writeDelayMs = 2000) {
    override fun actuallyReadData(): Data? {
        return dao.get()
    }

    override fun actuallyWriteData(data: Data) {
        dao.set(data)
    }
}

@Database(entities = [Data::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dataDao(): DataDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = INSTANCE
            ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context) = Room
            .databaseBuilder(context.applicationContext, AppDatabase::class.java, "App.db")
            .fallbackToDestructiveMigration()
            .build()
    }
}

@Dao
abstract class DataDao {
    @Insert
    protected abstract fun insert(data: Data)

    @Query("DELETE FROM data")
    abstract fun clear()

    @Query("SELECT * FROM data")
    abstract fun get(): Data?

    @Transaction
    open fun set(data: Data) {
        clear()
        insert(data)
    }
}
