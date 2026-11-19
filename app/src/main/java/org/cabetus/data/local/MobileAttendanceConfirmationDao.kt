package org.cabetus.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * モバイル出席の確認済みレコード。
 *
 * 削除・更新は提供しない。同じ主キーの再取り込みは IGNORE して既存行を保持する
 * （単一の [insertAll] は Room が 1 トランザクションで実行する）。
 */
@Dao
abstract class MobileAttendanceConfirmationDao {
    @Query("SELECT * FROM mobile_attendance_confirmations")
    abstract fun observeAll(): Flow<List<MobileAttendanceConfirmationEntity>>

    @Query(
        "SELECT * FROM mobile_attendance_confirmations WHERE courseCode = :code " +
            "ORDER BY date, period",
    )
    abstract suspend fun getForCourse(code: String): List<MobileAttendanceConfirmationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(rows: List<MobileAttendanceConfirmationEntity>): List<Long>
}
