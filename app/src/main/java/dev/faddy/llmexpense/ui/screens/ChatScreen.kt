package dev.faddy.llmexpense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.faddy.llmexpense.MainViewModel
import dev.faddy.llmexpense.utils.LocalMainVM


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val viewModel = LocalMainVM.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val allItems by viewModel.allExpenses.collectAsStateWithLifecycle(initialValue = emptyList())
    var questionText by remember { mutableStateOf(TextFieldValue("add 4kg mango of 20 ")) }

    LaunchedEffect(uiState) {
        viewModel.getUsedContextSize()
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("LLM Room DB App") }) }) { paddingValues ->
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
                is MainViewModel.UiState.ShowItems -> Text("Displaying items from database:")
                MainViewModel.UiState.ProcessingLlm -> Text("ProcessingLlm.")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}