package com.google.ai.edge.gallery.customtasks.livecamera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
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

private const val ANALYZE_INTERVAL_MS = 0L
private const val LIVE_FRAME_SIZE_PX = 192
private const val LIVE_ANALYSIS_TIMEOUT_MS = 12000L
private const val STILL_ANALYSIS_TIMEOUT_MS = 20000L

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

private enum class AnalysisSource {
  LIVE,
  STILL,
}

@Composable
fun LiveCameraAiScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
) {
  val context = LocalContext.current
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  var latestFrame by remember { mutableStateOf<Bitmap?>(null) }
  var lockedFrame by remember { mutableStateOf<Bitmap?>(null) }

  var mode by remember { mutableStateOf(LiveCameraMode.DESCRIBE) }
  var outputLanguage by remember { mutableStateOf(LiveCameraLanguage.JAPANESE) }
  var analysisText by remember { mutableStateOf("カメラを起動しています…") }
  var statusText by remember { mutableStateOf("カメラ映像を待っています") }
  var liveAnalysisEnabled by remember { mutableStateOf(true) }
  var analysisInProgress by remember { mutableStateOf(false) }
  var lastAnalyzedAt by remember { mutableLongStateOf(0L) }
  var currentRequestId by remember { mutableLongStateOf(0L) }
  var debugLogText by remember { mutableStateOf("Live Camera AI log is empty.") }

  val initStatus = modelManagerUiState.modelInitializationStatus[model.name]?.status
  val modelReady = initStatus == ModelInitializationStatusType.INITIALIZED

  fun appendDebugLog(message: String) {
    val entry = "${System.currentTimeMillis()} $message"
    debugLogText =
      (debugLogText.lineSequence().toList() + entry)
        .filter { it.isNotBlank() }
        .takeLast(60)
        .joinToString(separator = "\n")
  }

  LaunchedEffect(Unit) { setAppBarControlsDisabled(false) }

  LaunchedEffect(modelReady, liveAnalysisEnabled, latestFrame, analysisInProgress, lockedFrame) {
    if (!modelReady) {
      statusText = "画像対応モデルを初期化しています"
    } else if (analysisInProgress) {
      // Keep the in-progress label set by the active action.
    } else if (!liveAnalysisEnabled) {
      statusText = "LIVE解析はオフです"
    } else if (lockedFrame != null) {
      statusText = "同じ画像を解析できます"
    } else if (
      latestFrame != null &&
        statusText in
          setOf(
            "カメラ映像を待っています",
            "画像対応モデルを初期化しています",
            "次のカメラ映像を待っています",
            "カメラ映像を解析から再開する",
          )
    ) {
      statusText = "カメラ映像を解析できます"
    } else {
      statusText = "カメラ映像を待っています"
    }
  }

  LaunchedEffect(mode, outputLanguage, modelReady) {
    val frame = lockedFrame ?: return@LaunchedEffect
    if (!modelReady || analysisInProgress) {
      return@LaunchedEffect
    }
    val requestId = currentRequestId + 1L
    currentRequestId = requestId
    analysisInProgress = true
    statusText = "画像を解析しています"
    appendDebugLog("still:start requestId=$requestId mode=${mode.name} language=${outputLanguage.name} size=${frame.width}x${frame.height}")
    runAnalysis(
      model = model,
      bitmap = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false),
      mode = mode,
      outputLanguage = outputLanguage,
      source = AnalysisSource.STILL,
      requestId = requestId,
      onResult = { result ->
        if (requestId != currentRequestId) {
          return@runAnalysis
        }
        appendDebugLog("still:success requestId=$requestId resultLength=${result.length}")
        analysisText = result
        statusText = "静止画解析が完了しました"
        analysisInProgress = false
      },
      onError = { error ->
        if (requestId != currentRequestId) {
          return@runAnalysis
        }
        appendDebugLog("still:error requestId=$requestId reason=$error")
        analysisText = error
        statusText = "静止画解析に失敗しました"
        analysisInProgress = false
      },
    )
  }

  LaunchedEffect(modelReady, liveAnalysisEnabled, latestFrame, analysisInProgress, lockedFrame, mode, outputLanguage) {
    val frame = latestFrame ?: return@LaunchedEffect
    if (!modelReady || !liveAnalysisEnabled || analysisInProgress || lockedFrame != null) {
      return@LaunchedEffect
    }

    val now = System.currentTimeMillis()
    if (now - lastAnalyzedAt < ANALYZE_INTERVAL_MS) {
      return@LaunchedEffect
    }

    lastAnalyzedAt = now
    val requestId = currentRequestId + 1L
    currentRequestId = requestId
    analysisInProgress = true
    statusText = "カメラ映像を解析しています"
    appendDebugLog("live:start requestId=$requestId mode=${mode.name} language=${outputLanguage.name} size=${frame.width}x${frame.height}")
    val frameForAnalysis =
      frame
        .copy(frame.config ?: Bitmap.Config.ARGB_8888, false)
        .let { Bitmap.createScaledBitmap(it, LIVE_FRAME_SIZE_PX, LIVE_FRAME_SIZE_PX, true) }

    scope.launch {
      launch {
        kotlinx.coroutines.delay(LIVE_ANALYSIS_TIMEOUT_MS)
        if (requestId == currentRequestId && analysisInProgress) {
          model.runtimeHelper.stopResponse(model)
          analysisInProgress = false
          appendDebugLog("live:timeout requestId=$requestId")
          statusText = "カメラ映像を解析から再開する"
        }
      }
      runAnalysis(
        model = model,
        bitmap = frameForAnalysis,
        mode = mode,
        outputLanguage = outputLanguage,
        source = AnalysisSource.LIVE,
        requestId = requestId,
        onResult = { result ->
          if (requestId != currentRequestId) {
            return@runAnalysis
          }
          appendDebugLog("live:success requestId=$requestId resultLength=${result.length}")
          analysisText = result
          statusText = "カメラ映像の解析が完了しました"
          analysisInProgress = false
        },
        onError = { error ->
          if (requestId != currentRequestId) {
            return@runAnalysis
          }
          appendDebugLog("live:error requestId=$requestId reason=$error")
          analysisText = error
          statusText = "次のカメラ映像を待っています"
          analysisInProgress = false
        },
      )
    }
  }

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
            imageProxy.close()
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
                .clickable(enabled = modelReady && latestFrame != null) {
                  val captureBitmap = latestFrame ?: return@clickable
                  val requestId = currentRequestId + 1L
                  currentRequestId = requestId
                  analysisInProgress = true
                  statusText = "静止画を解析しています"
                  appendDebugLog("still:capture requestId=$requestId hasLatestFrame=${captureBitmap.width}x${captureBitmap.height}")
                  val stillFrame =
                    captureBitmap.copy(captureBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                  scope.launch {
                    launch {
                      kotlinx.coroutines.delay(STILL_ANALYSIS_TIMEOUT_MS)
                      if (requestId == currentRequestId && analysisInProgress) {
                        model.runtimeHelper.stopResponse(model)
                        analysisInProgress = false
                        appendDebugLog("still:timeout requestId=$requestId")
                        statusText = "静止画解析を再試行できます"
                      }
                    }
                    runAnalysis(
                      model = model,
                      bitmap = stillFrame,
                      mode = mode,
                      outputLanguage = outputLanguage,
                      source = AnalysisSource.STILL,
                      requestId = requestId,
                      onResult = { result ->
                        if (requestId != currentRequestId) {
                          return@runAnalysis
                        }
                        lockedFrame = stillFrame
                        analysisText = result
                        statusText = "静止画の解析が完了しました"
                        analysisInProgress = false
                        lastAnalyzedAt = System.currentTimeMillis()
                      },
                      onError = { error ->
                        if (requestId != currentRequestId) {
                          return@runAnalysis
                        }
                        analysisText = error
                        statusText = "静止画の解析に失敗しました"
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
              statusText = if (lockedFrame != null) "同じ画像を解析します" else "カメラ映像を解析できます"
              if (lockedFrame == null) {
                lastAnalyzedAt = 0L
              }
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
              statusText = if (lockedFrame != null) "同じ画像を解析します" else "カメラ映像を解析できます"
              if (lockedFrame == null) {
                lastAnalyzedAt = 0L
              }
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

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text(
            text = "LIVE解析",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = if (liveAnalysisEnabled) "オン" else "オフ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = liveAnalysisEnabled,
          onCheckedChange = { enabled ->
            liveAnalysisEnabled = enabled
            statusText =
              when {
                enabled && lockedFrame != null -> "同じ画像を解析します"
                enabled -> "カメラ映像を解析できます"
                else -> "LIVE解析はオフです"
              }
            if (enabled) {
              lastAnalyzedAt = 0L
            }
          },
        )
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
          modifier =
            Modifier.fillMaxWidth()
              .height(140.dp)
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          onClick = {
            val clipboard =
              context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val payload =
              buildString {
                append("status=")
                append(statusText)
                append('\n')
                append("analysis=")
                append(analysisText)
                append('\n')
                append("model=")
                append(model.displayName.ifEmpty { model.name })
                append('\n')
                append(debugLogText)
              }
            clipboard.setPrimaryClip(ClipData.newPlainText("live-camera-ai-log", payload))
            statusText = "詳細ログをコピーしました"
          },
        ) {
          Text(
            text = "詳細ログをコピー",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
      }
    }
  }
}

private fun runAnalysis(
  model: Model,
  bitmap: Bitmap,
  mode: LiveCameraMode,
  outputLanguage: LiveCameraLanguage,
  source: AnalysisSource,
  requestId: Long,
  onResult: (String) -> Unit,
  onError: (String) -> Unit,
) {
  val response = StringBuilder()
  model.runtimeHelper.resetConversation(model = model, supportImage = true, supportAudio = false)
  model.runtimeHelper.runInference(
    model = model,
    input = buildPrompt(mode = mode, outputLanguage = outputLanguage, source = source),
    images = listOf(bitmap),
    resultListener = { partialResult, done, _ ->
      if (partialResult.isNotEmpty()) {
        response.append(partialResult)
      }
      if (done) {
        val text = response.toString().replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) {
          onError("画像から十分な情報を読み取れませんでした。次の解析を試します。")
        } else {
          onResult(text)
        }
      }
    },
    cleanUpListener = {},
    onError = { message ->
      onError(message.ifBlank { "解析に失敗しました。" })
    },
  )
}

private fun buildPrompt(
  mode: LiveCameraMode,
  outputLanguage: LiveCameraLanguage,
  source: AnalysisSource,
): String {
  return when (mode) {
    LiveCameraMode.DESCRIBE ->
      if (source == AnalysisSource.LIVE) {
        "このカメラ映像の主要な内容だけを${outputLanguage.instructionName}でごく短く説明してください。1文だけで、いちばん目立つ人物、物体、場所の要点だけを答えてください。"
      } else {
        "このカメラ映像の内容を${outputLanguage.instructionName}で短めに説明してください。長すぎる列挙は避けつつ、人物、物体、場所、状況など主要な情報は分かる範囲で自然に含めてください。"
      }
    LiveCameraMode.TRANSLATE ->
      if (source == AnalysisSource.LIVE) {
        "このカメラ映像では大きく見える文字だけを対象にしてください。見える主要な文字列だけを短く抽出し、その内容だけを${outputLanguage.instructionName}で翻訳してください。文字が分からない場合は、文字なしと${outputLanguage.instructionName}で短く答えてください。"
      } else {
        "このカメラ映像では文字だけを対象にしてください。見える文字列をできるだけ正確に抽出し、その内容だけを${outputLanguage.instructionName}で自然に翻訳してください。画像全体の説明や物体説明は不要です。文字が見当たらない場合は、文字は見当たらないと${outputLanguage.instructionName}で短く答えてください。"
      }
  }
}
