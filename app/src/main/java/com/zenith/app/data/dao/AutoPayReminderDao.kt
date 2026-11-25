package com.zenith.app.data.dao

import androidx.room.*
import com.zenith.app.data.models.AutoPayReminderEntity
import com.zenith.app.data.models.FinanceScope
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoPayReminderDao {

    @Query("SELECT * FROM auto_pay_reminders WHERE isDeleted = 0 ORDER BY isPaused ASC, nextDueDateMillis ASC")
    fun getAllReminders(): Flow<List<AutoPayReminderEntity>>

    @Query("SELECT * FROM auto_pay_reminders WHERE isDeleted = 0 AND financeScope = :scope ORDER BY isPaused ASC, nextDueDateMillis ASC")
    fun getRemindersByScope(scope: FinanceScope): Flow<List<AutoPayReminderEntity>>

    @Query("SELECT * FROM auto_pay_reminders WHERE isDeleted = 0 AND isPaused = 0 AND nextDueDateMillis <= :untilMillis ORDER BY nextDueDateMillis ASC")
    fun getUpcomingReminders(untilMillis: Long): Flow<List<AutoPayReminderEntity>>

    @Query("SELECT * FROM auto_pay_reminders WHERE isDeleted = 0 AND id = :id LIMIT 1")
    suspend fun getReminderById(id: Long): AutoPayReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: AutoPayReminderEntity): Long

    @Update
    suspend fun updateReminder(reminder: AutoPayReminderEntity)

    @Query("UPDATE auto_pay_reminders SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteReminder(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE auto_pay_reminders SET isPaused = :isPaused, updatedAt = :now WHERE id = :id")
    suspend fun setPaused(id: Long, isPaused: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE auto_pay_reminders SET nextDueDateMillis = :nextDueDateMillis, lastPaidDateMillis = :lastPaidMillis, updatedAt = :now WHERE id = :id")
    suspend fun markAsPaid(id: Long, nextDueDateMillis: Long, lastPaidMillis: Long, now: Long = System.currentTimeMillis())
}
