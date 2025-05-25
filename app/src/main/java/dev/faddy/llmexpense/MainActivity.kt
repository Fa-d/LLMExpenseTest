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
import androidx.compose.foundation.interaction.Interaction
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat.recreate
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.faddy.llmexpense.data.ExpenseRecord
import dev.faddy.llmexpense.ui.components.createAlertDialog
import dev.faddy.llmexpense.ui.screens.ModelSelectionScreen
import dev.faddy.llmexpense.ui.theme.LLMExpenseTheme
import dev.faddy.llmexpense.utils.LocalMainVM
import dev.faddy.llmexpense.utils.LocalNavController
import dev.faddy.llmexpense.utils.PreferencesKeys

import dev.faddy.llmexpense.utils.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider


class MainActivity : ComponentActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            val viewModel = MainViewModel.Factory(context).create(MainViewModel::class.java)
            CompositionLocalProvider(
                LocalNavController provides navController, LocalMainVM provides viewModel
            ) {
                LLMExpenseTheme {
                    NavHost(
                        modifier = Modifier.fillMaxSize(),
                        navController = navController,
                        startDestination = "modelSelectionScreen"
                    ) {
                        composable("modelSelectionScreen") { ModelSelectionScreen() }
                        composable("ChatScreen") { AppScreen(viewModel = viewModel) }
                    }

                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val allItems by viewModel.allExpenses.collectAsStateWithLifecycle(initialValue = emptyList())
    var questionText by remember { mutableStateOf(TextFieldValue("add 4kg mango of 20 ")) }
    var selectedItem: ExpenseRecord? by remember { mutableStateOf(null) } // For update/delete UI
    LaunchedEffect(uiState) {

        viewModel.getUsedContextSize()
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modelPath = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SAVED_MODEL_PATH] ?: ""
    }.collectAsState(initial = "").value

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

