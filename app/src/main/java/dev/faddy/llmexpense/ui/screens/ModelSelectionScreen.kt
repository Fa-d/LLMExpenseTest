package dev.faddy.llmexpense.ui.screens

import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import dev.faddy.llmexpense.ui.components.createAlertDialog
import dev.faddy.llmexpense.utils.LocalMainVM
import dev.faddy.llmexpense.utils.LocalNavController
import dev.faddy.llmexpense.utils.PreferencesKeys
import dev.faddy.llmexpense.utils.checkGGUFFile
import dev.faddy.llmexpense.utils.copyModelFile
import dev.faddy.llmexpense.utils.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun ModelSelectionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = LocalMainVM.current
    val navController = LocalNavController.current
    var modelPath = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SAVED_MODEL_PATH] ?: ""
    }.collectAsState(initial = "").value

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            activityResult.data?.let {
                it.data?.let { uri ->
                    if (checkGGUFFile(uri, context)) {
                        copyModelFile(uri, context, onComplete = { path ->
                            modelPath = path.toString()
                            scope.launch {
                                context.dataStore.edit { preferences ->
                                    preferences[PreferencesKeys.SAVED_MODEL_PATH] = path.toString()
                                }
                            }
                            Log.e("TAG", "AppScreen: $path \n ${uri.toString()}")
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



    Column(
        modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center
    ) {
        if (modelPath.isNotEmpty()) {
            Log.e("TAG", "ModelSelectionScreen: $modelPath")
            viewModel.initializeLlm(modelPath)
            navController.navigate("ChatScreen")
        } else {
            Column {
                Text("Select model to continue")
                Spacer(Modifier.height(40.dp))
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

                }, modifier = Modifier) { Text("Select model") }
            }

        }
    }

}