package dev.faddy.llmexpense.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import dev.faddy.llmexpense.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths


fun checkGGUFFile(uri: Uri, context: Context): Boolean {
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val ggufMagicNumberBytes = ByteArray(4)
        inputStream.read(ggufMagicNumberBytes)
        return ggufMagicNumberBytes.contentEquals(byteArrayOf(71, 71, 85, 70))
    }
    return false
}

fun copyModelFile(
    uri: Uri,
    context: Context,
    onComplete: (path: String) -> Unit,
) {
    var fileName = ""
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        fileName = cursor.getString(nameIndex)
    }
    if (fileName.isNotEmpty()) {

        CoroutineScope(Dispatchers.IO).launch {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                FileOutputStream(File(context.filesDir, fileName)).use { outputStream ->
                    inputStream?.copyTo(outputStream)
                }
            }

            withContext(Dispatchers.Main) {
                onComplete(Paths.get(context.filesDir.absolutePath, fileName).toString())
            }
        }
    } else {
        Toast.makeText(
            context,
            context.getString(R.string.toast_invalid_file),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

fun JSONObject.toMap(): Map<String, *> = keys().asSequence().associateWith {
    when (val value = this[it])
    {
        is JSONArray ->
        {
            val map = (0 until value.length()).associate { Pair(it.toString(), value[it]) }
            JSONObject(map).toMap().values.toList()
        }
        is JSONObject -> value.toMap()
        JSONObject.NULL -> null
        else            -> value
    }
}

val test = """
         System: You are a financial data processing assistant.
        The user can tell you to insert multiple products in one sentence. If multiple products are detected, please return them as a JSON Array under the "items" key, and set the "action" to "insert_multiple_expenses".
        Your task is to understand the user's request about their expenses and convert it into a structured JSON command.
        The JSON command should have two main keys: "action" and "data".

        Possible "action" values are:
        - "insert_expense": When the user wants to add a new expense.
        - "get_all_expenses": When the user wants to see all expenses.
        - "get_expenses_by_category": When the user filters by category.
        - "get_expenses_above_price": When the user filters by a minimum price.
        - "search_expenses_by_name": When the user searches for expenses by name.
        - "insert_multiple_expenses": When the user wants to add multiple expenses at once.
        - "other_question": For any other query not directly related to the above actions.

        For "insert_expense", the "data" object must contain:
        - "itemName": String (e.g., "mangoes", "coffee")
        - "category": String (e.g., "Groceries", "Beverages", "Utilities". If not specified, use "General")
        - "quantity": Integer (e.g., 3, 1. If not specified, assume 1)
        - "pricePerUnit": Double (e.g., 5.0, 75.20)

        For "insert_multiple_expenses", the "data" object must contain an "items" key, which is a JSON array. Each object in the array should have:
        - "itemName": String
        - "category": String (Default: "General")
        - "quantity": Integer (Default: 1)
        - "pricePerUnit": Double (e.g., 5.0, 75.20)

        For "get_expenses_by_category", the "data" object must contain:
        - "category": String

        For "get_expenses_above_price", the "data" object must contain:
        - "minPrice": Double

        For "search_expenses_by_name", the "data" object must contain:
        - "nameQuery": String

        For "other_question", the "data" object should contain:
        - "original_question": String (the user's original question)

        If any required data for an action is missing, try to infer reasonably or set "action" to "other_question" and include the original query.
        Only output the valid JSON object. Do not include any other text, explanations, or markdown.

        User: _user_question

        Assistant (JSON Output Only):
""".trimIndent()