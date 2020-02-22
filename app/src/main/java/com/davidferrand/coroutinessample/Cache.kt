package com.davidferrand.coroutinessample

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.delay
import kotlin.properties.Delegates

abstract class Cache(
    private val tag: String
) : DescribableResource {

    protected var activeJobCount by Delegates.observable(0) { _, _, _ -> describeStatus() }
        private set

    suspend fun read(): Data? = log("long $tag.read() operation") {
        activeJobCount++
        try {
            actuallyReadData()
        } finally {
            activeJobCount--
        }
    }

    suspend fun write(data: Data) = log("long $tag.write() operation") {
        activeJobCount++
        try {
            actuallyWriteData(data)
        } finally {
            activeJobCount--
        }
    }


    // We add some artificial delays to read and write, once these are expired,
    // we want to actually read/write

    abstract suspend fun actuallyReadData(): Data?
    abstract suspend fun actuallyWriteData(data: Data)
    abstract suspend fun clear()
}

class RamCache(val statusLogger: StatusLogger) : Cache("ramCache") {
    private var cachedData: Data? = null

    override suspend fun actuallyReadData(): Data? {
        return cachedData
    }

    override suspend fun actuallyWriteData(data: Data) {
        cachedData = data
    }

    override suspend fun clear() {
        cachedData = null
        describeStatus()
    }

    override suspend fun initStatus() = describeStatus()
    override fun describeStatus() {
        statusLogger.log(
            StatusLogger.Status.RamStatus(
                isActive = activeJobCount > 0,
                description = cachedData.toString()
            )
        )
    }
}

class DiskCache(
    val dao: DataDao,
    val statusLogger: StatusLogger,
    val readDelayMs: Long = 500,
    val writeDelayMs: Long = 2000
) : Cache("diskCache") {
    /** Keep a lightweight description of the cache contents */
    private var latestDataDescription: String? = null

    override suspend fun actuallyReadData(): Data? {
        return dao.get(readDelayMs).also { latestDataDescription = it.toString() }
    }

    override suspend fun actuallyWriteData(data: Data) {
        dao.set(data, writeDelayMs).also { latestDataDescription = data.toString() }
    }

    override suspend fun clear() {
        dao.clear(writeDelayMs).also { latestDataDescription = null }
        describeStatus()
    }

    override suspend fun initStatus() {
        read()
        describeStatus()
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

    @Query("SELECT * FROM data")
    protected abstract suspend fun getInternal(): Data?

    @Insert
    protected abstract suspend fun insertInternal(data: Data)

    @Query("DELETE FROM data")
    protected abstract suspend fun clearInternal()

    @Transaction
    open suspend fun get(readDelayMs: Long): Data? {
        delay(readDelayMs)
        return getInternal()
    }

    @Transaction
    open suspend fun set(data: Data, writeDelayMs: Long) {
        clear(writeDelayMs)
        insertInternal(data)
    }

    @Transaction
    open suspend fun clear(writeDelayMs: Long) {
        delay(writeDelayMs)
        clearInternal()
    }
}
