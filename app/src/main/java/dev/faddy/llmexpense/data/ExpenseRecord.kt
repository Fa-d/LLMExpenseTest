package dev.faddy.llmexpense.data


import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expense_records")
data class ExpenseRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0, val itemName: String,      // e.g., "mangoes"
    val category: String,      // e.g., "Groceries", "Fruit"
    val quantity: Int,
    val pricePerUnit: Double,  // Price for one unit at the time of transaction
    val totalPrice: Double,    // Calculated: quantity * pricePerUnit
    val timestamp: Long = System.currentTimeMillis()
)
