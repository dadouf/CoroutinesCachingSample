package com.davidferrand.coroutinessample

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import java.io.IOException

abstract class Cache(
    private val tag: String
) : DescribableContents {

    // TODO check behavior when caches throw error (for whatever reason)

    val readAction = Action("$tag.read")
    val writeAction = Action("$tag.write")

    suspend fun read(): Data? = doWork(readAction) { actuallyRead() }
    suspend fun write(data: Data) = doWork(writeAction) { actuallyWrite(data) }
    suspend fun clear() = doWork(writeAction) { actuallyClear() }

    private suspend fun <T> doWork(action: Action, actualWork: suspend () -> T): T =
        log("${action.tag} operation") {
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

    private val cacheWriteScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override suspend fun actuallyRead(): Data? {
        // TODO do I need the coroutineScope here? why/not?
        val ramData: Data? =
            try {
                ram.read()
            } catch (t: Throwable) {
                log("Error thrown by ram.read(). CAUGHT, will return null.", t)
                null
            }

        if (ramData != null && ramData.isFresh()) {
            log("ramData is fresh, using it")
            return ramData

            // A possible optimization here would be: if ramData!=null but stale,
            // return it directly. That would be assuming that it's useless to read diskData
            // which is reasonable: in our implementation, diskData is never fresher than ramData,
            // since both are written at the same time.
        }

        val diskData: Data? =
            try {
                disk.read()
            } catch (t: Throwable) {
                log("Error thrown by disk.read(). CAUGHT, will return null", t)
                null
            }

        if (diskData != null && diskData.isFresh()) {
            log("diskData is fresh, using it and saving it to RAM")

            // A possible optimization here would be: if diskData!=null but stale,
            // write it to RAM as well.
            writeToRamAsSideEffect(diskData)

            return diskData
        }

        // None of the data was fresh, but stale is better than null
        return ramData ?: diskData
    }

    private fun writeToRamAsSideEffect(diskData: Data) {
        cacheWriteScope.launch {
            try {
                ram.write(diskData)
            } catch (t: Throwable) {
                log("Error thrown by ram.write() in cacheWriteScope. CAUGHT, will ignore.", t)
            }
        }
    }

    override suspend fun actuallyWrite(data: Data) {
        // TODO they don't need to be sequential, they could be launched as side effects, in which
        // case actuallyWrite doesn't need to be suspending -- what's the good pattern here?
        ram.write(data)
        disk.write(data)
    }

    override suspend fun actuallyClear() {
        ram.clear()
        disk.clear()
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
