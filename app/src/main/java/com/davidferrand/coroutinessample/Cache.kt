package com.davidferrand.coroutinessample

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates

abstract class Cache(
    private val tag: String,
    val readDelayMs: Long,
    val writeDelayMs: Long
) : DescribableResource {

    protected var activeJobCount by Delegates.observable(0) { _, _, _ -> describeStatus() }
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

    abstract suspend fun actuallyReadData(): Data?
    abstract suspend fun actuallyWriteData(data: Data)
}

class RamCache(val statusLogger: StatusLogger) :
    Cache("ramCache", readDelayMs = 10, writeDelayMs = 20) {
    private var cachedData: Data? = null

    override suspend fun actuallyReadData(): Data? {
        return cachedData
    }

    override suspend fun actuallyWriteData(data: Data) {
        cachedData = data
    }

    override fun describeStatus() {
        statusLogger.log(
            StatusLogger.Status.RamStatus(
                isActive = activeJobCount > 0,
                description = "RAM contents: ${cachedData.toString()}"
            )
        )
    }
}

class DiskCache(
    val dao: DataDao,
    val statusLogger: StatusLogger
) : Cache("diskCache", readDelayMs = 500, writeDelayMs = 2000) {
    /** Keep a lightweight description of the cache contents */
    private var latestDataDescription: String? = null

    override suspend fun actuallyReadData(): Data? {
        return dao.get().also { latestDataDescription = it.toString() }
    }

    override suspend fun actuallyWriteData(data: Data) {
        dao.set(data).also { latestDataDescription = data.toString() }
    }

    override fun describeStatus() {
        statusLogger.log(
            StatusLogger.Status.DiskStatus(
                isActive = activeJobCount > 0,
                description = latestDataDescription
            )
        )
    }
}

@Database(entities = [Data::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dataDao(): DataDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = INSTANCE
            ?: synchronized(this) { INSTANCE ?: buildDatabase(context).also { INSTANCE = it } }

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
