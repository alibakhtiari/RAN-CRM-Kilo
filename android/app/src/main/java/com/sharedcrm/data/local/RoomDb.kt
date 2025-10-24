package com.sharedcrm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow
import com.sharedcrm.data.local.entities.ContactEntity
import com.sharedcrm.data.local.entities.CallEntity
import com.sharedcrm.data.local.entities.SyncQueueEntity
import com.sharedcrm.data.local.entities.SyncLogEntity

@Database(
    entities = [
        ContactEntity::class,
        CallEntity::class,
        SyncQueueEntity::class,
        SyncLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactsDao(): ContactsDao
    abstract fun callsDao(): CallsDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shared_contact_crm.db"
                )
                    // TODO: Replace with proper Migrations once schema stabilizes
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

@Dao
interface ContactsDao {

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE phoneNormalized = :e164 LIMIT 1")
    suspend fun getByNormalizedPhone(e164: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ContactEntity>): List<Long>

    @Update
    suspend fun update(entity: ContactEntity)

    @Delete
    suspend fun delete(entity: ContactEntity)

    @Query("DELETE FROM contacts WHERE localId IN (:ids)")
    suspend fun deleteByLocalIds(ids: List<Long>)

    @Query("UPDATE contacts SET dirty = :dirty, lastModified = :lastModified WHERE localId = :localId")
    suspend fun markDirty(localId: Long, dirty: Boolean, lastModified: Long = System.currentTimeMillis())

    @Query("UPDATE contacts SET lastSyncedAt = :syncedAt, dirty = 0 WHERE localId = :localId")
    suspend fun markSynced(localId: Long, syncedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM contacts WHERE dirty = 1 ORDER BY lastModified ASC")
    suspend fun getDirtyContacts(): List<ContactEntity>

    @Query("UPDATE contacts SET conflict = :conflict WHERE localId = :localId")
    suspend fun setConflict(localId: Long, conflict: String?)
}

@Dao
interface CallsDao {

    @Query("SELECT * FROM calls WHERE contactServerId = :contactId ORDER BY startTime DESC")
    fun observeByContact(contactId: String): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE phoneNormalized = :e164 ORDER BY startTime DESC")
    fun observeByPhone(e164: String): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE uploaded = 0 ORDER BY startTime ASC")
    suspend fun getPendingUploads(): List<CallEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CallEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CallEntity>): List<Long>

    @Update
    suspend fun update(entity: CallEntity)

    @Query("UPDATE calls SET uploaded = 1, lastSyncedAt = :syncedAt WHERE localId = :localId")
    suspend fun markUploaded(localId: Long, syncedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(1) FROM calls WHERE phoneNormalized = :e164 AND startTime = :startTime")
    suspend fun existsByPhoneAndStart(e164: String, startTime: Long): Int

    @Delete
    suspend fun delete(entity: CallEntity)
}

@Dao
interface SyncQueueDao {

    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SyncQueueEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: SyncQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueAll(items: List<SyncQueueEntity>): List<Long>

    @Delete
    suspend fun dequeue(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE id IN (:ids)")
    suspend fun dequeueByIds(ids: List<Long>)

    @Query("UPDATE sync_queue SET attempts = attempts + 1, lastAttemptAt = :now WHERE id = :id")
    suspend fun bumpAttempt(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sync_queue WHERE attempts < :maxAttempts ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getRetryBatch(maxAttempts: Int = 5, limit: Int = 50): List<SyncQueueEntity>

    @Query("SELECT COUNT(1) FROM sync_queue")
    suspend fun count(): Int
}

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<SyncLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SyncLogEntity>): List<Long>

    @Query("DELETE FROM sync_log")
    suspend fun clear()

    @Query("DELETE FROM sync_log WHERE timestamp < :beforeEpoch")
    suspend fun prune(beforeEpoch: Long)
}