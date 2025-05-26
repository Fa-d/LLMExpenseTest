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

    private val expenseDao: ExpenseRecordDao = AppDatabase.getDatabase(context).expenseRecordDao()
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


    fun stopLlmResponseGeneration() {
        llmManager.stopResponseGeneration()
    }


    private fun createLlmPrompt(userQuestion: String): String {

        return """
        System: You are a personal finance assistant. generate the required data in a structured JSON format.  Ensure the following guidelines are followed when creating JSON structures:
    
    
       * Every response must contain a `status` field indicating whether the request was successful (`"success"`) or failed (`"error"`).
       * Include a `message` field for any additional information or error messages.
    
       * **Expenses and Income Data** should be presented with the following keys:
    
         * `category`: Name of the category (e.g., "Groceries", "Dining Out").
         * `total`: The total amount for the given category or time period (e.g., total expenses for a week).
         * `date_range`: A field specifying the time period for which the data is relevant (e.g., "2025-05-01 to 2025-05-31").
       
       * **Transactions** should include:
    
         * `transaction_id`: Unique identifier for the transaction.
         * `description`: Brief description of the transaction (e.g., "Supermarket purchase").
         * `amount`: The amount spent or received.
         * `category`: The category associated with the transaction.
         * `date`: Date of the transaction in `YYYY-MM-DD` format.
         * `payment_method`: (Optional) Payment method used (e.g., "Credit Card", "Cash").
       
       * **Insights** should include:
    
         * `insight_type`: Type of insight (e.g., "Spending Trend", "Budget Overrun").
         * `description`: A detailed description of the insight.
         * `value`: The numerical value or comparison (e.g., percentage or amount).
         * `date_range`: The time period the insight applies to.

        
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
                Log.d("MainViewModel", "partialResponse Response: ${partialResponse}")
                if (partialResponse.trim().startsWith("{") || _llmPartialResponse.value.trim()
                        .startsWith("{")
                ) {
                    _llmPartialResponse.value += partialResponse
                } else if (_llmPartialResponse.value.isBlank()) { // Only set if it's the beginning of a non-JSON message
                    _llmPartialResponse.value = partialResponse
                }
            }, onSuccess = { llmResponse ->
                _uiState.value = UiState.ShowMessage(llmResponse.response)
                usedContextSize.tryEmit(llmResponse.contextLengthUsed.toString())
                _llmPartialResponse.value = llmResponse.response
                executeIntent(llmResponse.response)
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

    private fun executeIntent(rawLlmResponse: String) {


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
            modelPath = modelPath, minP = 0.05f,
            temperature =  0.8f, // Example values - tune as needed
            storeChats = false, // Set based on whether you want LLM to remember chat history
            contextSize = 32768L, // Example context size
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

