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

    suspend fun read(): Data? = logAndTrack("$tag.read() operation") { actuallyRead() }
    suspend fun write(data: Data) = logAndTrack("$tag.write() operation") { actuallyWrite(data) }
    suspend fun clear() = logAndTrack("$tag.clear() operation") { actuallyClear() }

    abstract suspend fun actuallyRead(): Data?
    abstract suspend fun actuallyWrite(data: Data)
    abstract suspend fun actuallyClear()

    private suspend fun <T> logAndTrack(log: String, operation: suspend () -> T) = log(log) {
        activeJobCount++
        try {
            operation()
        } finally {
            activeJobCount--
        }
    }
}

class RamCache(val statusLogger: StatusLogger) : Cache("ramCache") {
    private var cachedData: Data? = null

    override suspend fun actuallyRead(): Data? {
        return cachedData
    }

    override suspend fun actuallyWrite(data: Data) {
        cachedData = data
    }

    override suspend fun actuallyClear() {
        cachedData = null
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

    override suspend fun actuallyRead(): Data? {
        return dao.get(readDelayMs).also { latestDataDescription = it.toString() }
    }

    override suspend fun actuallyWrite(data: Data) {
        dao.set(data, writeDelayMs).also { latestDataDescription = data.toString() }
    }

    override suspend fun actuallyClear() {
        dao.clear(writeDelayMs).also { latestDataDescription = null }
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
