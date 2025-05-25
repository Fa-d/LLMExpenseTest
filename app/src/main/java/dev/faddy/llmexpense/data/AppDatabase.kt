package dev.faddy.llmexpense.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ExpenseRecord::class],
    version = 1,
    exportSchema = false
) // Update version if schema changes
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseRecordDao(): ExpenseRecordDao // Changed from itemDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "llm_expense_database" // New database name or manage migration
                )
                     .fallbackToDestructiveMigration() // Use migration strategies for production
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
