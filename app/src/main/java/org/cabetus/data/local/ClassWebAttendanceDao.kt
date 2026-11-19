package org.cabetus.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassWebAttendanceDao {
    @Query("SELECT * FROM class_web_attendance")
    fun observeAll(): Flow<List<ClassWebAttendanceEntity>>

    @Query("SELECT * FROM class_web_attendance WHERE courseCode = :code")
    suspend fun getForCourse(code: String): List<ClassWebAttendanceEntity>

    @Query("SELECT * FROM class_web_attendance")
    suspend fun getAll(): List<ClassWebAttendanceEntity>

    @androidx.room.Transaction
    suspend fun upsertAll(rows: List<ClassWebAttendanceEntity>) { insertRows(rows) }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRows(rows: List<ClassWebAttendanceEntity>)

    @Query("SELECT MAX(fetchedAt) FROM class_web_attendance")
    fun getLatestFetchedAt(): Flow<Long?>
}
