package com.google.ai.edge.gallery.customtasks.livecamera

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

private const val ANALYZE_INTERVAL_MS = 2500L

private enum class LiveCameraMode(
  val label: String,
) {
  DESCRIBE(label = "画像解説"),
  TRANSLATE(label = "翻訳"),
}

private enum class LiveCameraLanguage(
  val label: String,
  val instructionName: String,
) {
  JAPANESE(label = "日本語", instructionName = "日本語"),
  ENGLISH(label = "English", instructionName = "English"),
  CHINESE(label = "中文", instructionName = "中文"),
  KOREAN(label = "한국어", instructionName = "한국어"),
}

@Composable
fun LiveCameraAiScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  var latestFrame by remember { mutableStateOf<Bitmap?>(null) }

  var mode by remember { mutableStateOf(LiveCameraMode.DESCRIBE) }
  var outputLanguage by remember { mutableStateOf(LiveCameraLanguage.JAPANESE) }
  var analysisText by remember { mutableStateOf("カメラを起動しています…") }
  var statusText by remember { mutableStateOf("ライブ解析を待機しています") }
  var analysisInProgress by remember { mutableStateOf(false) }
  var lastAnalyzedAt by remember { mutableLongStateOf(0L) }

  val initStatus = modelManagerUiState.modelInitializationStatus[model.name]?.status
  val modelReady = initStatus == ModelInitializationStatusType.INITIALIZED

  LaunchedEffect(analysisInProgress) { setAppBarControlsDisabled(analysisInProgress) }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(bottom = bottomPadding),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().weight(1f),
      color = Color.Black,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        LiveCameraView(
          modifier = Modifier.fillMaxSize(),
          renderPreview = true,
          cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
          onBitmap = { bitmap: Bitmap, imageProxy: ImageProxy ->
            latestFrame = bitmap
            if (!modelReady) {
              imageProxy.close()
              return@LiveCameraView
            }

            val now = System.currentTimeMillis()
            if (analysisInProgress || now - lastAnalyzedAt < ANALYZE_INTERVAL_MS) {
              imageProxy.close()
              return@LiveCameraView
            }

            lastAnalyzedAt = now
            analysisInProgress = true
            statusText = "AI解析中"
            val frameForAnalysis = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            imageProxy.close()
            scope.launch {
              runAnalysis(
                model = model,
                bitmap = frameForAnalysis,
                mode = mode,
                outputLanguage = outputLanguage,
                onResult = { result ->
                  analysisText = result
                  statusText = "ライブ解析を継続中"
                  analysisInProgress = false
                },
                onError = { error ->
                  analysisText = error
                  statusText = "再試行待ち"
                  analysisInProgress = false
                },
              )
            }
          },
        )

        Box(
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier =
              Modifier.size(78.dp)
                .background(Color.Black.copy(alpha = 0.28f), CircleShape)
                .padding(10.dp)
                .background(
                  Color.White.copy(alpha = if (analysisInProgress) 0.45f else 0.96f),
                  CircleShape,
                )
                .border(width = 2.dp, color = Color.White, shape = CircleShape)
                .clickable(enabled = modelReady && latestFrame != null && !analysisInProgress) {
                  val captureBitmap = latestFrame ?: return@clickable
                  analysisInProgress = true
                  statusText = "静止画を高精度解析中"
                  val stillFrame =
                    captureBitmap.copy(captureBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                  scope.launch {
                    runAnalysis(
                      model = model,
                      bitmap = stillFrame,
                      mode = mode,
                      outputLanguage = outputLanguage,
                      onResult = { result ->
                        analysisText = result
                        statusText = "静止画解析が完了しました"
                        analysisInProgress = false
                        lastAnalyzedAt = System.currentTimeMillis()
                      },
                      onError = { error ->
                        analysisText = error
                        statusText = "静止画解析に失敗しました"
                        analysisInProgress = false
                      },
                    )
                  }
                },
          )
        }

        Surface(
          color = Color.Black.copy(alpha = 0.45f),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
          Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
              text = model.displayName.ifEmpty { model.name },
              style = MaterialTheme.typography.labelLarge,
              color = Color.White,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = statusText,
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.85f),
            )
          }
        }

        if (!modelReady) {
          Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp,
              )
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = "画像対応モデルを初期化しています",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          }
        }
      }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
      ) {
        items(LiveCameraMode.entries) { entry ->
          val selected = entry == mode
          Surface(
            modifier =
              Modifier.border(
                width = 1.dp,
                color =
                  if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(999.dp),
              ),
            color =
              if (selected) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(999.dp),
            onClick = {
              mode = entry
              statusText = "ライブ解析を待機しています"
              lastAnalyzedAt = 0L
            },
          ) {
            Text(
              text = entry.label,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
              style = MaterialTheme.typography.labelLarge,
              color =
                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
      ) {
        items(LiveCameraLanguage.entries) { entry ->
          val selected = entry == outputLanguage
          Surface(
            modifier =
              Modifier.border(
                width = 1.dp,
                color =
                  if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(999.dp),
              ),
            color =
              if (selected) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(999.dp),
            onClick = {
              outputLanguage = entry
              statusText = "ライブ解析を待機しています"
              lastAnalyzedAt = 0L
            },
          ) {
            Text(
              text = entry.label,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
              style = MaterialTheme.typography.labelLarge,
              color =
                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      Text(
        text = "AIの結果",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = analysisText,
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

private fun runAnalysis(
  model: Model,
  bitmap: Bitmap,
  mode: LiveCameraMode,
  outputLanguage: LiveCameraLanguage,
  onResult: (String) -> Unit,
  onError: (String) -> Unit,
) {
  val response = StringBuilder()
  model.runtimeHelper.resetConversation(model = model, supportImage = true, supportAudio = false)
  model.runtimeHelper.runInference(
    model = model,
    input = buildPrompt(mode = mode, outputLanguage = outputLanguage),
    images = listOf(bitmap),
    resultListener = { partialResult, done, _ ->
      if (partialResult.isNotEmpty()) {
        response.append(partialResult)
      }
      if (done) {
        val text =
          response
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "結果を取得できませんでした。" }
        onResult(text)
      }
    },
    cleanUpListener = {},
    onError = { message ->
      onError(message.ifBlank { "解析に失敗しました。" })
    },
  )
}

private fun buildPrompt(mode: LiveCameraMode, outputLanguage: LiveCameraLanguage): String {
  return when (mode) {
    LiveCameraMode.DESCRIBE ->
      "このカメラ映像の内容を${outputLanguage.instructionName}で短く説明してください。人物、物体、場所、状況を分かる範囲で具体的に述べてください。"
    LiveCameraMode.TRANSLATE ->
      "このカメラ映像では文字だけを対象にしてください。見える文字列をできるだけ正確に抽出し、その内容だけを${outputLanguage.instructionName}で自然に翻訳してください。画像全体の説明や物体説明は不要です。文字が見当たらない場合は、文字は見当たらないと${outputLanguage.instructionName}で短く答えてください。"
  }
}
