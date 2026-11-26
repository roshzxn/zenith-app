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

    @Query("SELECT * FROM auto_pay_reminders WHERE serverId = :serverId LIMIT 1")
    suspend fun getReminderByServerId(serverId: String): AutoPayReminderEntity?

    @Query("SELECT * FROM auto_pay_reminders WHERE syncStatus = 'PENDING_CREATE' AND isDeleted = 0")
    suspend fun getPendingCreates(): List<AutoPayReminderEntity>

    @Query("SELECT * FROM auto_pay_reminders WHERE syncStatus = 'PENDING_UPDATE' AND isDeleted = 0")
    suspend fun getPendingUpdates(): List<AutoPayReminderEntity>

    @Query("SELECT * FROM auto_pay_reminders WHERE syncStatus = 'PENDING_DELETE' OR isDeleted = 1")
    suspend fun getPendingDeletes(): List<AutoPayReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: AutoPayReminderEntity): Long

    @Update
    suspend fun updateReminder(reminder: AutoPayReminderEntity)

    @Delete
    suspend fun deleteReminder(reminder: AutoPayReminderEntity)

    @Query("UPDATE auto_pay_reminders SET isDeleted = 1, syncStatus = 'PENDING_DELETE', updatedAt = :now WHERE id = :id")
    suspend fun softDeleteReminder(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE auto_pay_reminders SET isPaused = :isPaused, syncStatus = CASE WHEN serverId IS NOT NULL THEN 'PENDING_UPDATE' ELSE syncStatus END, updatedAt = :now WHERE id = :id")
    suspend fun setPaused(id: Long, isPaused: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE auto_pay_reminders SET nextDueDateMillis = :nextDueDateMillis, lastPaidDateMillis = :lastPaidMillis, syncStatus = CASE WHEN serverId IS NOT NULL THEN 'PENDING_UPDATE' ELSE syncStatus END, updatedAt = :now WHERE id = :id")
    suspend fun markAsPaid(id: Long, nextDueDateMillis: Long, lastPaidMillis: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE auto_pay_reminders SET isDeleted = 1, syncStatus = 'PENDING_DELETE', updatedAt = :now WHERE financeScope = 'PERSONAL' AND serverId IS NOT NULL AND serverId != ''")
    suspend fun markAllPersonalRemindersPendingDelete(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM auto_pay_reminders WHERE financeScope = 'PERSONAL' AND (serverId IS NULL OR serverId = '')")
    suspend fun deleteLocalOnlyPersonalReminders()

    @Query("DELETE FROM auto_pay_reminders")
    suspend fun deleteAllReminders()
}
