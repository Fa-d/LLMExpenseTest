package dev.faddy.llmexpense

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.faddy.llmexpense.data.ExpenseRecord
import dev.faddy.llmexpense.ui.components.createAlertDialog
import dev.faddy.llmexpense.ui.theme.LLMExpenseTheme
import dev.faddy.llmexpense.utils.checkGGUFFile
import dev.faddy.llmexpense.utils.copyModelFile

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LLMExpenseTheme {
                AppScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val allItems by viewModel.allExpenses.collectAsStateWithLifecycle(initialValue = emptyList())
    var loadedPath by remember { mutableStateOf("") }
    var questionText by remember { mutableStateOf(TextFieldValue("add 4kg mango of 20 ")) }
    var selectedItem: ExpenseRecord? by remember { mutableStateOf(null) } // For update/delete UI
    LaunchedEffect(uiState) {

        viewModel.getUsedContextSize()
    }
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            activityResult.data?.let {
                it.data?.let { uri ->
                    if (checkGGUFFile(uri, context)) {
                        copyModelFile(uri, context, onComplete = { path ->
                            loadedPath = path
                            Log.e("TAG", "AppScreen: $path")
                            Toast.makeText(context, "Model copied to $path", Toast.LENGTH_SHORT)
                                .show()
                        })
                    } else {
                        createAlertDialog(
                            dialogTitle = "Invalid File",
                            dialogText = "The selected file is not a valid GGUF file.",
                            dialogPositiveButtonText = "OK",
                            onPositiveButtonClick = {},
                            dialogNegativeButtonText = null,
                            onNegativeButtonClick = null,
                        )
                    }
                }
            }
        }


    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("LLM Room DB App") })
        }) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = { questionText = it },
                    label = { Text("Enter your question or command") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    if (questionText.text.isNotBlank()) {
                        viewModel.handleUserQuestion(questionText.text)
                        questionText = TextFieldValue("") // Clear input after sending
                    }
                }) {
                    Text("Ask LLM")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons (for testing CRUD and LLM Init)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        setType("application/octet-stream")
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS,
                            ).toUri(),
                        )
                    }
                    launcher.launch(intent)
                }) {
                    Text("Load LLM")
                }
                Button(
                    onClick = {
                        if (getModelPath(context)) {
                            val modelPath =
                                "/data/user/0/dev.faddy.llmexpense/files/i queried .gguf"
                            viewModel.initializeLlm(modelPath)
                        }
                    }, modifier = Modifier.weight(1f)
                ) {
                    Text("Init LLM")
                }
                Button(
                    onClick = {

                    }, modifier = Modifier.weight(1f)
                ) {
                    Text("Insert Test Item")
                }
            }


            Spacer(modifier = Modifier.height(16.dp))

            Text("used Context Size:" + viewModel.usedContextSize.collectAsState().value)
            Spacer(modifier = Modifier.height(16.dp))
            when (uiState) {
                is MainViewModel.UiState.Idle -> Text("Ready.")
                is MainViewModel.UiState.Loading -> CircularProgressIndicator()
                is MainViewModel.UiState.ShowError -> Text("Error: ${(uiState as MainViewModel.UiState.ShowError).message}")
                is MainViewModel.UiState.ShowMessage -> Text("Message: ${(uiState as MainViewModel.UiState.ShowMessage).message}")
                is MainViewModel.UiState.ShowItems -> {
                    // Data is displayed below in the LazyColumn observing allItems
                    Text("Displaying items from database:")
                }

                MainViewModel.UiState.ProcessingLlm -> Text("ProcessingLlm.")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display Database Items (Observing allItems Flow)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = allItems) { item ->
                    ItemRow(item = item, onItemSelected = { selectedItem = it })
                    Divider() // Optional divider between items
                }
            }

            // Simple UI for Update/Delete selected item (Optional)
            selectedItem?.let { item ->
                AlertDialog(
                    onDismissRequest = { selectedItem = null },
                    title = { Text("Manage Item") },
                    text = {
                        Column {
                            Text("Selected Item: ${item.itemName}")
                            // Add fields to edit item properties if implementing update UI
                        }
                    },
                    confirmButton = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = {
                                // TODO: Implement Update logic based on edited fields
                                // viewModel.updateItem(updatedItem)
                                selectedItem = null
                            }) {
                                Text("Update")
                            }
                            Button(onClick = {

                                selectedItem = null
                            }) {
                                Text("Delete")
                            }
                        }
                    },
                    dismissButton = {
                        Button(onClick = { selectedItem = null }) {
                            Text("Cancel")
                        }
                    })
            }
        }
    }
}

private fun RowScope.getModelPath(context: Context): Boolean {

    var isAvailable = false
    context.filesDir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.endsWith(".gguf")) {
            isAvailable = true
        }
    }
    if (!isAvailable) {
        Toast.makeText(context, "Model not available", Toast.LENGTH_SHORT).show()
    }
    return isAvailable
}

@Composable
fun ItemRow(item: ExpenseRecord, onItemSelected: (ExpenseRecord) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onItemSelected(item) }, // Make row clickable to select item
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = item.itemName, style = MaterialTheme.typography.bodyMedium)
            Text(text = item.category, style = MaterialTheme.typography.bodySmall)
        }
        Text(text = "$${item.totalPrice}", style = MaterialTheme.typography.bodyMedium)
    }
}

