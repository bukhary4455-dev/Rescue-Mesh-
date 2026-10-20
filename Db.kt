package com.rescuemesh.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Contact(@PrimaryKey val userId: String, val name: String, val pubKey: String, val lastSeen: Long = 0)

/** status: QUEUED, SENT (handed to nearby link, unconfirmed), DELIVERED (recipient ACK), RECEIVED (incoming) */
@Entity data class Msg(
    @PrimaryKey val id: String, val peerId: String, val outgoing: Boolean,
    val text: String, val ts: Long, val status: String
)

@Dao interface ContactDao {
    @Query("SELECT * FROM Contact ORDER BY name") fun all(): Flow<List<Contact>>
    @Query("SELECT * FROM Contact WHERE userId=:id") fun flow(id: String): Flow<Contact?>
    @Query("SELECT * FROM Contact WHERE userId=:id") suspend fun get(id: String): Contact?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: Contact)
    @Query("UPDATE Contact SET lastSeen=:t WHERE userId=:id") suspend fun seen(id: String, t: Long)
    @Query("DELETE FROM Contact WHERE userId=:id") suspend fun delete(id: String)
}

@Dao interface MsgDao {
    @Query("SELECT * FROM Msg WHERE peerId=:p ORDER BY ts") fun chat(p: String): Flow<List<Msg>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: Msg): Long
    @Query("SELECT * FROM Msg WHERE peerId=:p AND outgoing=1 AND status IN ('QUEUED','SENT') ORDER BY ts")
    suspend fun pendingFor(p: String): List<Msg>
    @Query("UPDATE Msg SET status='SENT' WHERE id=:id AND status='QUEUED'") suspend fun setSent(id: String)
    @Query("UPDATE Msg SET status='DELIVERED' WHERE id=:id AND peerId=:p AND outgoing=1") suspend fun delivered(id: String, p: String)
    @Query("DELETE FROM Msg WHERE peerId=:p") suspend fun clear(p: String)
    @Query("SELECT COUNT(*) FROM Msg WHERE outgoing=1 AND status IN ('QUEUED','SENT')") fun pendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM Msg WHERE outgoing=1 AND status='DELIVERED'") fun deliveredCount(): Flow<Int>
}

@Database(entities = [Contact::class, Msg::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun contacts(): ContactDao
    abstract fun msgs(): MsgDao
    companion object {
        fun get(c: Context) = Room.databaseBuilder(c, AppDb::class.java, "rescuemesh.db").build()
    }
}
