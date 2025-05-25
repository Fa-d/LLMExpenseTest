package dev.faddy.llmexpense.llm


import io.shubham0204.smollm.SmolLM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.time.measureTime

@Single
class LlmManager() {
    private val instance = SmolLM()
    private var responseGenerationJob: Job? = null
    private var modelInitJob: Job? = null
    private var isInstanceLoaded = false
    var isInferenceOn = false

    data class SmolLMInitParams(
        val modelPath: String,
        val minP: Float,
        val temperature: Float,
        val storeChats: Boolean,
        val contextSize: Long,
        val chatTemplate: String,
        val nThreads: Int,
        val useMmap: Boolean,
        val useMlock: Boolean,
    )

    data class SmolLMResponse(
        val response: String,
        val generationSpeed: Float,
        val generationTimeSecs: Int,
        val contextLengthUsed: Int,
    )

    fun create(
        initParams: SmolLMInitParams,
        onError: (Exception) -> Unit,
        onSuccess: () -> Unit,
    ) {
        try {
            modelInitJob = CoroutineScope(Dispatchers.Default).launch {
                if (isInstanceLoaded) {
                    close()
                }
                instance.create(
                    initParams.modelPath,
                    initParams.minP,
                    initParams.temperature,
                    initParams.storeChats,
                    initParams.contextSize,
                    initParams.chatTemplate,
                    initParams.nThreads,
                    initParams.useMmap,
                    initParams.useMlock,
                )

/*
                instance.addSystemPrompt("You are a data processing assistant for a finance application.\n" +
                        "Your task is to extract item details from the user's natural language input, calculate the total price, and provide them in a structured JSON format.\n" +
                        "The JSON object must contain the following fields:\n" +
                        "- \"operation\": A string indicating the action to be performed (e.g., \"insert\", \"update\", \"delete\", \"read\").\n" +
                        "- \"item_name\": A string representing the name of the item.\n" +
                        "- \"quantity\": A number representing the quantity of the item.\n" +
                        "- \"price_per_unit\": A number representing the cost of a single unit of the item.\n" +
                        "- \"total_price\": A number calculated as quantity * price_per_unit.\n" +
                        "\n" +
                        "Infer \"insert\" if the user is adding a new item.\n" +
                        "Only output the valid JSON object. Do not include any other text or explanations.\n")
*/


                withContext(Dispatchers.Main) {
                    isInstanceLoaded = true
                    onSuccess()
                }
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    fun getResponse(
        query: String,
        responseTransform: (String) -> String,
        onPartialResponseGenerated: (String) -> Unit,
        onSuccess: (SmolLMResponse) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        try {
            responseGenerationJob = CoroutineScope(Dispatchers.Default).launch {
                isInferenceOn = true
                var response = ""
                val duration = measureTime {
                    instance.getResponse(query).collect { piece ->
                        response += piece
                        withContext(Dispatchers.Main) {
                            onPartialResponseGenerated(response)
                        }
                    }
                }
                response = responseTransform(response)
                // once the response is generated
                // add it to the messages database
                withContext(Dispatchers.Main) {
                    isInferenceOn = false
                    onSuccess(
                        SmolLMResponse(
                            response = response,
                            generationSpeed = instance.getResponseGenerationSpeed(),
                            generationTimeSecs = duration.inWholeSeconds.toInt(),
                            contextLengthUsed = instance.getContextLengthUsed(),
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            isInferenceOn = false
            onCancelled()
        } catch (e: Exception) {
            isInferenceOn = false
            onError(e)
        }
    }

    fun stopResponseGeneration() {
        responseGenerationJob?.let { cancelJobIfActive(it) }
    }

    fun close() {
        stopResponseGeneration()
        modelInitJob?.let { cancelJobIfActive(it) }
        instance.close()
        isInstanceLoaded = false
    }

    private fun cancelJobIfActive(job: Job) {
        if (job.isActive) {
            job.cancel()
        }
    }
}