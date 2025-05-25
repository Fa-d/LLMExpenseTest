package dev.faddy.llmexpense

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toLowerCase
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.faddy.llmexpense.data.AppDatabase
import dev.faddy.llmexpense.data.ExpenseRecord
import dev.faddy.llmexpense.data.ExpenseRecordDao
import dev.faddy.llmexpense.llm.LlmManager
import dev.faddy.llmexpense.llm.LlmManager.SmolLMInitParams
import dev.faddy.llmexpense.utils.toMap
import io.shubham0204.smollm.GGUFReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

class MainViewModel(context: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST") return MainViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private val expenseDao: ExpenseRecordDao =
        AppDatabase.getDatabase(context).expenseRecordDao()
    private val llmManager = LlmManager()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val usedContextSize = MutableStateFlow("")

    val allExpenses: Flow<List<ExpenseRecord>> = expenseDao.getAllExpenses().catch { e ->
        Log.e("MainViewModel", "Error fetching all expenses", e)
        emit(emptyList()) // Emit empty list on error
        _uiState.value = UiState.ShowError("Database error: ${e.localizedMessage}")
    }.asStateFlow(emptyList(), Dispatchers.IO)/*  .stateIn(
          scope = viewModelScope,
          started = SharingStarted.WhileSubscribed(5000),
          initialValue = emptyList()
      )*/


    private val _llmPartialResponse = MutableStateFlow<String>("")
    val llmPartialResponse: StateFlow<String> = _llmPartialResponse.asStateFlow()


    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState() // For general loading (e.g., LLM init)
        object ProcessingLlm : UiState() // Specifically for LLM inference
        data class ShowItems(val items: List<ExpenseRecord>) :
            UiState() // Can use allItems flow directly

        data class ShowError(val message: String) : UiState()
        data class ShowMessage(val message: String) :
            UiState() // For messages from LLM or operations
    }

    // Define possible intents derived from user questions
    sealed class UserIntent {
        object GetAllExpenses : UserIntent() // Renamed for clarity
        data class GetItemsByCategory(val category: String) : UserIntent()
        data class GetItemsAbovePrice(val minPrice: Double) : UserIntent()
        data class SearchItemsByName(val name: String) : UserIntent()
        data class InsertItem(val item: ExpenseRecord) : UserIntent()
        data class UpdateItem(val item: ExpenseRecord) : UserIntent()
        data class DeleteItemById(val id: Int) : UserIntent()
        data class ParsedLlmData(val action: String, val data: Map<String, Any>) : UserIntent()
        object DeleteAllItems : UserIntent()
        data class OtherQuestion(val question: String, val rawLlmResponse: String) :
            UserIntent() // For questions not directly querying DB or LLM errors

        object Unknown : UserIntent()
    }


    fun stopLlmResponseGeneration() {
        llmManager.stopResponseGeneration()
    }


    /*  init {
          initializeLlm("/data/user/0/dev.faddy.llmexpense/files/qwen1_5-0_5b-chat-q5_0.gguf")
      }*/

    private fun createLlmPrompt(userQuestion: String): String {

        return """
        System: You are a financial data processing assistant.
        The user can tell you to insert multiple products in one sentence. 
        Your task is to understand the user's request about their expenses and convert it into a structured JSON command.
        The JSON command should have two main keys: "action" and "data".
        
        Possible "action" values are:
        - "insert_expense": When the user wants to add a new expense.
        - "other_question": For any other query not directly related to the above actions.
        
        For "insert_expense", the "data" object must contain:
        - "itemName": String (e.g., "mangoes", "coffee")
        - "category": String (e.g., "Groceries", "Beverages", "Utilities". If not specified, use "General")
        - "quantity": Integer (e.g., 3, 1. If not specified, assume 1)
        - "pricePerUnit": Double (e.g., 5.0, 75.20)
        
        For "other_question", the "data" object should contain: 
        - "original_question": String (the user's original question)

        If any required data for an action is missing, try to infer reasonably or set "action" to "other_question" and include the original query.
        Only output the valid JSON object. Do not include any other text, explanations, or markdown.

        
        User: $userQuestion

        Assistant (JSON Output Only):
        """.trimIndent()
    }


    fun handleUserQuestion(userQuestion: String) {
        if (llmManager.isInferenceOn) {
            _uiState.value = UiState.ShowMessage("LLM is currently processing. Please wait.")
            return
        }
        _uiState.value = UiState.ProcessingLlm
        _llmPartialResponse.value = ""

        val fullPrompt = createLlmPrompt(userQuestion) // Use the structured prompt
        llmManager
        llmManager.getResponse(
            query = fullPrompt, // Send the full, structured prompt
            responseTransform = { it }, onPartialResponseGenerated = { partialResponse ->
                // Only show partial response if it's likely JSON or a message
                if (partialResponse.trim().startsWith("{") || _llmPartialResponse.value.trim()
                        .startsWith("{")
                ) {
                    _llmPartialResponse.value += partialResponse
                } else if (_llmPartialResponse.value.isBlank()) { // Only set if it's the beginning of a non-JSON message
                    _llmPartialResponse.value = partialResponse
                }
            }, onSuccess = { llmResponse ->
                Log.d("MainViewModel", "LLM Raw Response: ${llmResponse.response}")
                usedContextSize.tryEmit(llmResponse.contextLengthUsed.toString())
                _llmPartialResponse.value =
                    llmResponse.response // Show final raw response for debugging

                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        val (intent, _) = interpretLlmJsonResponse(
                            llmResponse.response, userQuestion
                        )
                        executeIntent(intent, llmResponse.response)
                    }


                }
            }, onCancelled = {
                _uiState.value = UiState.Idle
                _llmPartialResponse.value = "LLM inference cancelled."
            }, onError = { e ->
                Log.e("MainViewModel", "LLM Error", e)
                _uiState.value = UiState.ShowError("LLM Error: ${e.message ?: "Unknown error"}")
                _llmPartialResponse.value = "LLM Error: ${e.message ?: "Unknown error"}"
                _uiState.value = UiState.Idle
            })
    }

    private suspend fun executeIntent(intent: UserIntent, rawLlmResponse: String) {
        when (intent) {
            is UserIntent.ParsedLlmData -> {
                val jsonObj = JSONObject(rawLlmResponse)
                _uiState.value = UiState.ShowMessage(jsonObj.toString())
                // handleParsedLlmData(intent.action, map)
            }


            is UserIntent.OtherQuestion -> {
                _uiState.value = UiState.ShowMessage("LLM: ${intent.rawLlmResponse}")
                _llmPartialResponse.value = intent.rawLlmResponse
            }

            is UserIntent.Unknown -> {
                _uiState.value =
                    UiState.ShowError("Could not understand the request. Raw LLM output: $rawLlmResponse")
            }
            // Fallback for older direct intents if interpretLlmJsonResponse returns them (should be rare)
            is UserIntent.GetAllExpenses -> {
                _uiState.value = UiState.Idle // UI observes allExpenses
                _llmPartialResponse.value = "Showing all expenses."
            }

            else -> {
                _uiState.value =
                    UiState.ShowError("Unhandled intent type. Raw LLM output: $rawLlmResponse")
            }
        }
        if (intent !is UserIntent.OtherQuestion && intent !is UserIntent.ParsedLlmData) {
            // For direct actions that don't rely on observing a flow for feedback
            _uiState.value = UiState.Idle
        }
    }


    private suspend fun handleParsedLlmData(action: String, data: Map<String, *>) {
        Log.d("MainViewModel", "Handling Action: $action, Data: $data")
        when (action.toLowerCase(Locale.current)) {
            "insert_expense" -> {
                try {
                    val itemName = data["itemName"] as? String ?: "Unknown Item"
                    val category = data["category"] as? String ?: "General"
                    val quantity = (data["quantity"] as? Number)?.toInt() ?: 1
                    val pricePerUnit = (data["pricePerUnit"] as? Number)?.toDouble() ?: 0.0

                    if (itemName != "Unknown Item" && pricePerUnit > 0.0) {
                        val expenseRecord = ExpenseRecord(
                            itemName = itemName,
                            category = category,
                            quantity = quantity,
                            pricePerUnit = pricePerUnit,
                            totalPrice = quantity * pricePerUnit
                        )
                        expenseDao.insertExpense(expenseRecord)
                        _uiState.value = UiState.ShowMessage("Expense inserted: $itemName")
                        _llmPartialResponse.value =
                            "Inserted: $itemName ($quantity x $pricePerUnit = ${expenseRecord.totalPrice} Taka, Category: $category)"
                    } else {
                        _uiState.value =
                            UiState.ShowError("Could not insert expense: Missing name or price.")
                        _llmPartialResponse.value =
                            "Failed to insert: Invalid data received from LLM."
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error inserting expense from LLM data", e)
                    _uiState.value = UiState.ShowError("Error inserting: ${e.message}")
                    _llmPartialResponse.value = "Error processing insert request."
                }
            }

            "insert_multiple_expenses" -> {
                val itemsList = data["items"] as? List<Map<String, Any>>
                if (itemsList.isNullOrEmpty()) {
                    _uiState.value = UiState.ShowError("No items provided for multiple insertion.")
                    _llmPartialResponse.value = "Failed to insert multiple: No item data from LLM."
                    return
                }
                val insertedNames = mutableListOf<String>()
                val failedItems = mutableListOf<String>()
                itemsList.forEach { itemData ->
                    try {
                        val itemName = itemData["itemName"] as? String ?: "Unknown Item"
                        val category = itemData["category"] as? String ?: "General"
                        val quantity = (itemData["quantity"] as? Number)?.toInt() ?: 1
                        val pricePerUnit = (itemData["pricePerUnit"] as? Number)?.toDouble() ?: 0.0

                        if (itemName != "Unknown Item" && pricePerUnit > 0.0) {
                            val expenseRecord = ExpenseRecord(
                                itemName = itemName,
                                category = category,
                                quantity = quantity,
                                pricePerUnit = pricePerUnit,
                                totalPrice = quantity * pricePerUnit
                            )
                            expenseDao.insertExpense(expenseRecord)
                            insertedNames.add("$itemName ($quantity x $pricePerUnit)")
                        } else {
                            failedItems.add(itemName)
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error processing one item in multiple insert", e)
                        failedItems.add((itemData["itemName"] as? String) ?: "Unnamed Item")
                    }
                }
                var message = "Inserted: ${insertedNames.joinToString()}. "
                if (failedItems.isNotEmpty()) message += "Failed for: ${failedItems.joinToString()}."
                _uiState.value =
                    UiState.ShowMessage(message.take(100) + if (message.length > 100) "..." else "")
                _llmPartialResponse.value = message
            }

            "get_all_expenses" -> {
                // UI is already observing `allExpenses`. Message for clarity.
                _uiState.value = UiState.Idle
                _llmPartialResponse.value = "Showing all expenses."
            }

            "get_expenses_by_category" -> {
                val category = data["category"] as? String
                if (category != null) {
                    // UI needs to observe a different flow or filter allExpenses
                    // For now, just show a message.
                    _uiState.value =
                        UiState.ShowMessage("Filtering by category: $category (UI update needed)")
                    _llmPartialResponse.value =
                        "To see items in category '$category', please check the main list (filtering logic to be enhanced in UI)."
                } else {
                    _uiState.value = UiState.ShowError("Category not specified.")
                }
            }

            "search_expenses_by_name" -> {
                val nameQuery = data["nameQuery"] as? String
                if (nameQuery != null) {
                    _uiState.value =
                        UiState.ShowMessage("Searching for: $nameQuery (UI update needed)")
                    _llmPartialResponse.value =
                        "To see items matching '$nameQuery', please check the main list (filtering logic to be enhanced in UI)."
                } else {
                    _uiState.value = UiState.ShowError("Search term not specified.")
                }
            }

            "get_expenses_above_price" -> {
                val minPrice = (data["minPrice"] as? Number)?.toDouble()
                if (minPrice != null) {
                    _uiState.value =
                        UiState.ShowMessage("Filtering for expenses above $minPrice (UI update needed)")
                    _llmPartialResponse.value =
                        "To see items above $minPrice, please check the main list (filtering logic to be enhanced in UI)."
                } else {
                    _uiState.value = UiState.ShowError("Minimum price not specified.")
                }
            }

            else -> {
                _uiState.value = UiState.ShowError("Unknown action from LLM: $action")
                _llmPartialResponse.value = "Received an unknown command: $action"
            }
        }
        // Return to Idle unless an error state was already set or it's a continuous flow observation
        if (_uiState.value !is UiState.ShowError) {
            _uiState.value = UiState.Idle
        }
    }

    // --- Interpret LLM JSON Response ---
    private fun interpretLlmJsonResponse(
        llmResponse: String, originalQuestion: String
    ): Pair<UserIntent, Map<String, Any>> {
        try {
            // Attempt to find a valid JSON object within the response string
            val jsonStartIndex = llmResponse.indexOfFirst { it == '{' }
            val jsonEndIndex = llmResponse.indexOfLast { it == '}' }

            if (jsonStartIndex != -1 && jsonEndIndex != -1 && jsonEndIndex > jsonStartIndex) {
                val jsonString = llmResponse.substring(jsonStartIndex, jsonEndIndex + 1)
                Log.d("MainViewModel", "Attempting to parse JSON: $jsonString")
                val jsonObj = JSONObject(jsonString)
                val action = jsonObj.optString("action")
                val dataObj = jsonObj.optJSONObject("data")
                val dataMap = mutableMapOf<String, Any>()

                dataObj?.keys()?.forEach { key ->
                    val value = dataObj.get(key)
                    if (value is org.json.JSONArray) {
                        val list = mutableListOf<Map<String, Any>>()
                        for (i in 0 until value.length()) {
                            if (value.get(i) is org.json.JSONObject) {
                                val itemObj = value.getJSONObject(i)
                                val itemMap =
                                    itemObj.keys().asSequence().associateWith { itemObj.get(it) }
                                list.add(itemMap)
                            }
                        }
                        dataMap[key] = list
                    } else {
                        dataMap[key] = value
                    }
                }

                if (action.isNotBlank()) {
                    return Pair(UserIntent.ParsedLlmData(action, dataMap), dataMap)
                }
            }
            Log.w("MainViewModel", "No valid JSON object found in LLM response or action is blank.")
        } catch (e: Exception) { // Catch org.json.JSONException and other parsing errors
            Log.e("MainViewModel", "Failed to parse LLM JSON response: $llmResponse", e)
        }
        // Fallback if JSON parsing fails or action is not recognized
        return Pair(UserIntent.OtherQuestion(originalQuestion, llmResponse), emptyMap())
    }

    fun getUsedContextSize(): String {
        return usedContextSize.value
    }


    // Function to initialize the LLM
    fun initializeLlm(modelPath: String) {
        Log.e("TAG", "ModelSelectionScreen: $modelPath")

        val chatTemplate = runBlocking {
            val ggufReader = GGUFReader()
            ggufReader.load(modelPath)
            return@runBlocking ggufReader.getChatTemplate() ?: ""
        }

        _uiState.value = UiState.Loading // Indicate LLM initialization is loading
        val initParams = SmolLMInitParams(
            modelPath = modelPath, minP = 0.05f, // Example values - tune as needed
            temperature = 1.5f, // Example values - tune as needed
            storeChats = false, // Set based on whether you want LLM to remember chat history
            contextSize = 2048L, // Example context size
            chatTemplate = chatTemplate, // Specify chat template if needed
            nThreads = Runtime.getRuntime().availableProcessors() - 2, // Use available threads
            useMmap = true, // Use memory mapping if possible
            useMlock = false // Avoid memory locking on Android generally
        )

        llmManager.create(initParams = initParams, onError = { e ->
            viewModelScope.launch {
                _uiState.value = UiState.ShowError("Failed to initialize LLM: ${e.message}")
                _uiState.value = UiState.Idle // Return to idle state
            }
        }, onSuccess = {
            viewModelScope.launch {
                _uiState.value = UiState.ShowMessage("LLM initialized successfully!")
                _uiState.value = UiState.Idle // Return to idle state
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        llmManager.close() // Close LLM resources when ViewModel is cleared
    }

}

// Helper function to convert Flow to StateFlow with initial value
fun <T> Flow<T>.asStateFlow(initialValue: T, coroutineContext: CoroutineContext): StateFlow<T> =
    this.stateIn(
        scope = kotlinx.coroutines.CoroutineScope(coroutineContext),
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = initialValue
    )

