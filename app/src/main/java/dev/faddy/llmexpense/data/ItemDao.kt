package dev.faddy.llmexpense.data


import androidx.room.*
import kotlinx.coroutines.flow.Flow



@Dao
interface ExpenseRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(record: ExpenseRecord): Long // Return Long for the new rowId

    @Update
    suspend fun updateExpense(record: ExpenseRecord)


    @Query("SELECT * FROM expense_records WHERE id = :recordId")
    fun getExpenseById(recordId: Int): Flow<ExpenseRecord?> // Use Flow for reactive updates


    @Query("SELECT * FROM expense_records ORDER BY timestamp DESC")
    fun getAllExpenses(): Flow<List<ExpenseRecord>>

    @Query("SELECT * FROM expense_records WHERE category = :category ORDER BY timestamp DESC")
    fun getExpensesByCategory(category: String): Flow<List<ExpenseRecord>>

    @Query("SELECT * FROM expense_records WHERE itemName LIKE '%' || :nameQuery || '%' ORDER BY timestamp DESC")
    fun searchExpensesByName(nameQuery: String): Flow<List<ExpenseRecord>>

    @Query("SELECT * FROM expense_records WHERE totalPrice >= :minPrice ORDER BY timestamp DESC")
    fun getExpensesAbovePrice(minPrice: Double): Flow<List<ExpenseRecord>>

}


