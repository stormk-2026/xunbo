package com.stormg.xunbo.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.stormg.xunbo.core.model.CalibrationStore
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrCodeStore
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenCalibration
import com.stormg.xunbo.core.navigation.IrWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "ir_codes")
data class CodeRow(
    @PrimaryKey val name: String,
    val userCode1: Int,
    val userCode2: Int,
    val commandCode: Int,
)

@Entity(tableName = "settings")
data class SettingRow(
    @PrimaryKey val name: String,
    val value: String,
)

@Entity(tableName = "manual_records")
data class RecordRow(
    @PrimaryKey val id: String,
    val atMs: Long,
    val json: String,
)

@Dao
interface DebugDao {
    @Query("SELECT * FROM ir_codes")
    suspend fun allCodes(): List<CodeRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCodes(rows: List<CodeRow>)

    @Query("DELETE FROM ir_codes WHERE name = :name")
    suspend fun deleteCode(name: String)

    @Query("SELECT value FROM settings WHERE name = :name")
    suspend fun setting(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setting(row: SettingRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: RecordRow)

    @Query("SELECT * FROM manual_records ORDER BY atMs DESC")
    fun records(): kotlinx.coroutines.flow.Flow<List<RecordRow>>

    @Query("SELECT * FROM manual_records ORDER BY atMs")
    suspend fun allRecords(): List<RecordRow>
}

@Database(entities = [CodeRow::class, SettingRow::class, RecordRow::class], version = 1, exportSchema = true)
abstract class DebugDatabase : RoomDatabase() {
    abstract fun dao(): DebugDao
}

class RoomCodes(private val db: DebugDatabase, scope: CoroutineScope) : IrCodeStore {
    private val mutableCodes = MutableStateFlow<Map<RemoteKey, IrCode>>(emptyMap())
    override val codes = mutableCodes.asStateFlow()
    private val mutex = Mutex()
    val initialized = scope.async { reload() }

    private suspend fun reload() {
        mutableCodes.value =
            db.dao().allCodes().associate { row ->
                val name = RemoteKey.valueOf(row.name)
                name to IrCode(name, row.userCode1, row.userCode2, row.commandCode)
            }
    }

    override suspend fun save(code: IrCode) = saveAll(listOf(code))

    override suspend fun saveAll(codes: List<IrCode>) =
        mutex.withLock {
            initialized.await()
            require(codes.all(IrWire::valid)) { "INVALID_IR_BYTE" }
            try {
                db.withTransaction {
                    db.dao().saveCodes(codes.map { CodeRow(it.name.name, it.userCode1, it.userCode2, it.commandCode) })
                }
            } finally {
                withContext(NonCancellable) { reload() }
            }
        }

    override suspend fun delete(key: RemoteKey) =
        mutex.withLock {
            initialized.await()
            withContext(NonCancellable) {
                db.dao().deleteCode(key.name)
                reload()
            }
        }
}

class RoomCalibration(private val dao: DebugDao) : CalibrationStore {
    override suspend fun load(): ScreenCalibration? = dao.setting("calibration")?.let { Json.decodeFromString(it) }

    override suspend fun save(calibration: ScreenCalibration) = dao.setting(SettingRow("calibration", Json.encodeToString(calibration)))
}

@Serializable
enum class ManualOutcome { UNLABELED, ONE_STEP, NO_CHANGE, OVERSHOOT, WRONG_DIRECTION, UNKNOWN }

@Serializable
data class ManualRecord(
    val id: String,
    val sessionId: String,
    val atMs: Long,
    val device: String?,
    val format: String,
    val key: RemoteKey,
    val queueMs: Long,
    val writeMs: Long,
    val before: String?,
    val after: String?,
    val captureRequested: Boolean,
    val error: String?,
    val outcome: ManualOutcome = ManualOutcome.UNLABELED,
    val source: String = "MANUAL",
    val calibration: ScreenCalibration? = null,
    val writeStartedAtElapsedMs: Long? = null,
    val beforeFrameAtElapsedMs: Long? = null,
    val afterFrameAtElapsedMs: Long? = null,
    val sampleDelayMs: Long = 0,
    val writeReturned: Boolean = false,
)
