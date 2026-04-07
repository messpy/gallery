/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private var handledSharedShareKey: String? = null
  private val modelManagerViewModel: ModelManagerViewModel by viewModels()
  private var splashScreenAboutToExit: Boolean = false
  private var contentSet: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    fun setContent() {
      if (contentSet) {
        return
      }

      setContent {
        GalleryTheme {
          Surface(modifier = Modifier.fillMaxSize()) {
            GalleryApp(modelManagerViewModel = modelManagerViewModel)

            // Fade out a "mask" that has the same color as the background of the splash screen
            // to reveal the actual app content.
            var startMaskFadeout by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { startMaskFadeout = true }
            AnimatedVisibility(
              !startMaskFadeout,
              enter = fadeIn(animationSpec = snap(0)),
              exit =
                fadeOut(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)),
            ) {
              Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
              )
            }
          }
        }
      }

      @OptIn(ExperimentalApi::class)
      ExperimentalFlags.enableBenchmark = false

      contentSet = true
    }

    modelManagerViewModel.loadModelAllowlist()

    // Show splash screen.
    val splashScreen = installSplashScreen()

    // Set the content when the system-provided splash screen is not shown.
    //
    // This is necessary on some Android versions where the splash screen is optimized away (e.g.,
    // after a force-quit) to ensure the main content is displayed immediately and correctly.
    lifecycleScope.launch {
      delay(1000)
      if (!splashScreenAboutToExit) {
        setContent()
      }
    }

    // Cross-fade transition from the splash screen to the main content.
    //
    // The logic performs the following key actions:
    // 1. Synchronizes Timing: It calculates the remaining duration of the default icon
    //    animation. It then delays its own animations to ensure the custom fade-out begins just
    //    before the original icon animation would have finished.
    // 2. Initiates a cross-fade:
    //    - Fade out the splash screen.
    //    - Fade in the main content.
    // 3. Cleans up: An `onEnd` listener on the fade-out animator calls
    //    `splashScreenView.remove()` to properly remove the splash screen from the view hierarchy
    //    once it's fully transparent.
    splashScreen.setOnExitAnimationListener { splashScreenView ->
      splashScreenAboutToExit = true

      val now = System.currentTimeMillis()
      val iconAnimationStartMs = splashScreenView.iconAnimationStartMillis
      val duration = splashScreenView.iconAnimationDurationMillis
      val fadeOut = ObjectAnimator.ofFloat(splashScreenView.view, View.ALPHA, 1f, 0f)
      fadeOut.interpolator = DecelerateInterpolator()
      fadeOut.duration = 300L
      fadeOut.doOnEnd { splashScreenView.remove() }
      lifecycleScope.launch {
        val setContentDelay = duration - (now - iconAnimationStartMs) - 300
        if (setContentDelay > 0) {
          delay(setContentDelay)
        }
        setContent()
        fadeOut.start()
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    // Keep the screen on while the app is running for better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    handleSharedImageIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleSharedImageIntent(intent)
  }

  override fun onResume() {
    super.onResume()

    firebaseAnalytics?.logEvent(
      FirebaseAnalytics.Event.APP_OPEN,
      bundleOf(
        "app_version" to BuildConfig.VERSION_NAME,
        "os_version" to Build.VERSION.SDK_INT.toString(),
        "device_model" to Build.MODEL,
      ),
    )
  }

  companion object {
    private const val TAG = "AGMainActivity"
    private const val SHARE_IMAGE_NOTIFICATION_ID = 41231
    private const val SHARE_TEXT_NOTIFICATION_ID = 41232
    private const val SHARE_IMAGE_TIMEOUT_MS = 120_000L
  }

  private fun handleSharedImageIntent(intent: Intent?) {
    val sharedImageUri = extractSharedImageUri(intent)
    val sharedText = extractSharedText(intent)
    val shareKey = sharedImageUri?.toString() ?: sharedText ?: return
    if (handledSharedShareKey == shareKey) {
      return
    }
    handledSharedShareKey = shareKey

    lifecycleScope.launch {
      waitForModelAllowlist()

      if (sharedImageUri != null) {
        handleSharedImage(sharedImageUri)
      } else if (sharedText != null) {
        handleSharedText(sharedText)
      }
    }
  }

  private suspend fun handleSharedImage(sharedImageUri: Uri) {
    val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_ASK_IMAGE)
    val model = resolveSharedImageModel()
    if (task == null || model == null) {
      ShareImageNotificationHelper.showError(
        applicationContext,
        SHARE_IMAGE_NOTIFICATION_ID,
        getString(R.string.share_image_no_model_downloaded),
      )
      return
    }

    val bitmap = decodeSharedImage(sharedImageUri)
    if (bitmap == null) {
      ShareImageNotificationHelper.showError(
        applicationContext,
        SHARE_IMAGE_NOTIFICATION_ID,
        getString(R.string.share_image_decoding_failed),
      )
      return
    }

    ShareImageNotificationHelper.showProcessing(
      applicationContext,
      SHARE_IMAGE_NOTIFICATION_ID,
      getString(R.string.share_image_notification_processing_title),
      getString(R.string.share_image_notification_processing_content).format(
        model.displayName.ifEmpty { model.name }
      ),
    )

    val initialized = ensureModelInitialized(task = task, model = model)
    if (!initialized) {
      val initError =
        modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]?.error
          ?.takeIf { it.isNotBlank() }
          ?: getString(R.string.unknown_error)
      ShareImageNotificationHelper.showError(
        applicationContext,
        SHARE_IMAGE_NOTIFICATION_ID,
        initError,
        getString(R.string.share_image_notification_error_title),
      )
      return
    }

    runSharedImageInference(model = model, bitmap = bitmap)
  }

  private suspend fun handleSharedText(sharedText: String) {
    val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)
    val model = resolveSharedTextModel()
    if (task == null || model == null) {
      ShareImageNotificationHelper.showError(
        applicationContext,
        SHARE_TEXT_NOTIFICATION_ID,
        getString(R.string.share_text_no_model_downloaded),
        getString(R.string.share_text_notification_error_title),
      )
      return
    }

    ShareImageNotificationHelper.showProcessing(
      applicationContext,
      SHARE_TEXT_NOTIFICATION_ID,
      getString(R.string.share_text_notification_processing_title),
      getString(R.string.share_text_notification_processing_content).format(
        model.displayName.ifEmpty { model.name }
      ),
    )

    val initialized = ensureModelInitialized(task = task, model = model)
    if (!initialized) {
      val initError =
        modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]?.error
          ?.takeIf { it.isNotBlank() }
          ?: getString(R.string.unknown_error)
      ShareImageNotificationHelper.showError(
        applicationContext,
        SHARE_TEXT_NOTIFICATION_ID,
        initError,
        getString(R.string.share_text_notification_error_title),
      )
      return
    }

    runSharedTextInference(model = model, sharedText = sharedText)
  }

  private fun extractSharedText(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_SEND) {
      return null
    }
    if (intent.type != "text/plain") {
      return null
    }
    return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
  }

  private fun resolveSharedTextModel(): Model? {
    val selectedModel = modelManagerViewModel.getSelectedModel()
    val downloadStatuses = modelManagerViewModel.uiState.value.modelDownloadStatus
    if (
      selectedModel != null &&
        selectedModel.isLlm &&
        downloadStatuses[selectedModel.name]?.status == ModelDownloadStatusType.SUCCEEDED
    ) {
      return selectedModel
    }

    val chatTask = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT) ?: return null
    return chatTask.models.firstOrNull { model ->
      model.isLlm &&
        downloadStatuses[model.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
  }

  private suspend fun ensureModelInitialized(
    task: com.google.ai.edge.gallery.data.Task,
    model: Model,
  ): Boolean {
    val currentStatus = modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]
    if (currentStatus?.status == ModelInitializationStatusType.INITIALIZED) {
      return true
    }

    modelManagerViewModel.initializeModel(context = this, task = task, model = model)

    val start = System.currentTimeMillis()
    while (coroutineContext.isActive && System.currentTimeMillis() - start < SHARE_IMAGE_TIMEOUT_MS) {
      when (modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]?.status) {
        ModelInitializationStatusType.INITIALIZED -> return true
        ModelInitializationStatusType.ERROR -> return false
        else -> delay(100)
      }
    }
    return false
  }

  private fun runSharedTextInference(model: Model, sharedText: String) {
    model.runtimeHelper.resetConversation(model = model, supportImage = false, supportAudio = false)
    val responseBuilder = StringBuilder()
    model.runtimeHelper.runInference(
      model = model,
      input = getString(R.string.share_text_prompt_prefix) + sharedText,
      resultListener = { partialResult, done, _ ->
        if (partialResult.isNotEmpty()) {
          responseBuilder.append(partialResult)
        }
        if (!done) {
          return@runInference
        }
        val finalText =
          responseBuilder
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { getString(R.string.share_text_empty_result) }
        ShareImageNotificationHelper.showResult(
          applicationContext,
          SHARE_TEXT_NOTIFICATION_ID,
          finalText,
          getString(R.string.share_text_notification_result_title),
        )
      },
      cleanUpListener = {},
      onError = { message ->
        ShareImageNotificationHelper.showError(
          applicationContext,
          SHARE_TEXT_NOTIFICATION_ID,
          message.ifBlank { getString(R.string.unknown_error) },
          getString(R.string.share_text_notification_error_title),
        )
      },
      coroutineScope = lifecycleScope,
    )
  }

  private fun extractSharedImageUri(intent: Intent?): Uri? {
    if (intent?.action != Intent.ACTION_SEND) {
      return null
    }
    if (intent.type?.startsWith("image/") != true) {
      return null
    }
    @Suppress("DEPRECATION")
    return intent.getParcelableExtra(Intent.EXTRA_STREAM)
  }

  private suspend fun waitForModelAllowlist() {
    while (coroutineContext.isActive && modelManagerViewModel.uiState.value.loadingModelAllowlist) {
      delay(100)
    }
  }

  private fun resolveSharedImageModel(): Model? {
    val selectedModel = modelManagerViewModel.getSelectedModel()
    val downloadStatuses = modelManagerViewModel.uiState.value.modelDownloadStatus
    if (
      selectedModel != null &&
        selectedModel.llmSupportImage &&
        downloadStatuses[selectedModel.name]?.status == ModelDownloadStatusType.SUCCEEDED
    ) {
      return selectedModel
    }

    val askImageTask = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_ASK_IMAGE) ?: return null
    return askImageTask.models.firstOrNull { model ->
      model.llmSupportImage &&
        downloadStatuses[model.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
  }

  private fun decodeSharedImage(uri: Uri): Bitmap? {
    return try {
      val source = ImageDecoder.createSource(contentResolver, uri)
      ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        decoder.isMutableRequired = false
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to decode shared image", e)
      null
    }
  }

  private fun runSharedImageInference(model: Model, bitmap: Bitmap) {
    model.runtimeHelper.resetConversation(model = model, supportImage = true, supportAudio = false)
    val responseBuilder = StringBuilder()
    model.runtimeHelper.runInference(
      model = model,
      input = getString(R.string.share_image_prompt),
      images = listOf(bitmap),
      resultListener = { partialResult, done, _ ->
        if (partialResult.isNotEmpty()) {
          responseBuilder.append(partialResult)
        }
        if (!done) {
          return@runInference
        }
        val finalText =
          responseBuilder
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { getString(R.string.share_image_empty_result) }
        ShareImageNotificationHelper.showResult(
          applicationContext,
          SHARE_IMAGE_NOTIFICATION_ID,
          finalText,
          getString(R.string.share_image_notification_result_title),
        )
      },
      cleanUpListener = {},
      onError = { message ->
        ShareImageNotificationHelper.showError(
          applicationContext,
          SHARE_IMAGE_NOTIFICATION_ID,
          message.ifBlank { getString(R.string.unknown_error) },
          getString(R.string.share_image_notification_error_title),
        )
      },
      coroutineScope = lifecycleScope,
    )
  }
}
