package com.davidferrand.coroutinessample

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

abstract class Cache(
    private val tag: String
) : DescribableContents {

    // TODO check behavior when caches throw error (for whatever reason)

    val readAction = Action("$tag.read")
    val writeAction = Action("$tag.write")

    suspend fun read(): Data? = log("$tag.read() operation") {
        readAction.activityCount++
        try {
            if (readAction.nextResult == ProgrammableAction.NextResult.SUCCEED) {
                actuallyRead()
            } else {
                throw IOException("Read failed")
            }
        } finally {
            readAction.activityCount--
        }
    }

    suspend fun write(data: Data) = log("$tag.write() operation") {
        writeAction.activityCount++
        try {
            if (writeAction.nextResult == ProgrammableAction.NextResult.SUCCEED) {
                actuallyWrite(data)
            } else {
                throw IOException("Write failed")
            }
        } finally {
            writeAction.activityCount--
        }
    }

    suspend fun clear() = log("$tag.clear() operation") {
        writeAction.activityCount++
        try {
            if (writeAction.nextResult == ProgrammableAction.NextResult.SUCCEED) {
                actuallyClear()
            } else {
                throw IOException("Clear failed")
            }
        } finally {
            writeAction.activityCount--
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

    private val cachesWriteScope = CoroutineScope(Dispatchers.Default)

    override suspend fun actuallyRead(): Data? {
        val ramData = ram.read()

        if (ramData != null && ramData.isFresh()) {
            log("ramData is fresh, using it")
            return ramData
        }

        val diskData = disk.read()
        if (diskData != null && diskData.isFresh()) {
            log("diskData is fresh, using it")
            cachesWriteScope.launch { ram.write(diskData) }

            return diskData
        }

        return null
    }

    override suspend fun actuallyWrite(data: Data) {
        // TODO they don't need to be sequential
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
