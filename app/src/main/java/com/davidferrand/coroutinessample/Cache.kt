package com.davidferrand.coroutinessample

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

abstract class Cache(private val tag: String) : DescribableContents {
    val readAction = Action("$tag.read")
    val writeAction = Action("$tag.write")

    suspend fun read(): Data? = doWork(readAction) { actuallyRead() }
    suspend fun write(data: Data) = doWork(writeAction) { actuallyWrite(data) }
    suspend fun clear() = doWork(writeAction) { actuallyClear() }

    /**
     * This method and the difference between read and actuallyRead only exists because of
     * some debug features (programming and watching). A real implementation would directly
     * implement read, write and clear
     */
    private suspend fun <T> doWork(action: Action, actualWork: suspend () -> T): T =
        logSuspending("${action.tag} operation") {
            action.activityCount++
            try {
                if (action.nextResult == ProgrammableAction.NextResult.SUCCEED) {
                    actualWork.invoke()
                } else {
                    throw IOException("${action.tag} failed")
                }
            } finally {
                action.activityCount--
            }
        }

    protected abstract suspend fun actuallyRead(): Data?
    protected abstract suspend fun actuallyWrite(data: Data)
    protected abstract suspend fun actuallyClear()

}

class CompoundCache(
    val ram: Cache,
    val disk: Cache
) : Cache("localCache") {

    private val cacheWriteScope = CoroutineScope(SupervisorJob())

    override suspend fun actuallyRead(): Data? {
        val ramData: Data? = ram.readSafely()

        if (ramData != null) {
            // If ramData exists, even if stale, return it directly. In our implementation
            // diskData is never fresher than ramData, since both are written at the same time.
            // So don't waste resources reading from disk: use ramData.

            return ramData
        }

        val diskData: Data? = disk.readSafely()

        if (diskData != null) {
            // If diskData exists, even if stale, save it to RAM
            ram.writeAsync(diskData)
        }

        return diskData
    }

    override suspend fun actuallyWrite(data: Data) {
        ram.write(data) // Write to RAM synchronously: it's cheap and probably best for consistency
        disk.writeAsync(data) // Write to DISK a-synchronously: we don't need to wait for it
    }

    override suspend fun actuallyClear() {
        ram.clear() // Write to RAM synchronously: it's cheap and probably best for consistency
        disk.clearAsync() // Write to DISK a-synchronously: we don't need to wait for it
    }

    /**
     * [Cache.read] and turn any error into null
     */
    private suspend fun Cache.readSafely(): Data? = try {
        read()
    } catch (t: Throwable) {
        log("Error thrown by read(). CAUGHT, will return null.", t)
        null
    }

    /**
     * Launch [Cache.write] in a side-effect scope and return immediately without suspending.
     */
    private fun Cache.writeAsync(data: Data) {
        cacheWriteScope.launch {
            try {
                write(data)
            } catch (t: Throwable) {
                log("Error thrown by write() in cacheWriteScope. CAUGHT, will ignore.", t)
            }
        }
    }

    /**
     * Launch [Cache.clear] in a side-effect scope and return immediately without suspending.
     */
    private fun Cache.clearAsync() {
        cacheWriteScope.launch {
            try {
                clear()
            } catch (t: Throwable) {
                log("Error thrown by clear() in cacheWriteScope. CAUGHT, will ignore.", t)
            }
        }
    }

    override fun describeContents() =
        "[RAM] ${ram.describeContents()}\n[DISK] ${disk.describeContents()}"
}


class RamCache : Cache("ramCache") {
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

    override fun describeContents() = cachedData.toString()
}

class DiskCache(
    val dao: DataDao
) : Cache("diskCache") {
    /** Keep a lightweight description of the cache contents */
    private var latestDataDescription: String? = null

    override suspend fun actuallyRead(): Data? {
        return dao.get(readAction.delayMs ?: 0).also { latestDataDescription = it.toString() }
    }

    override suspend fun actuallyWrite(data: Data) {
        dao.set(data, writeAction.delayMs ?: 0).also { latestDataDescription = data.toString() }
    }

    override suspend fun actuallyClear() {
        dao.clear(writeAction.delayMs ?: 0).also { latestDataDescription = null }
    }

    override fun describeContents() = latestDataDescription
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
