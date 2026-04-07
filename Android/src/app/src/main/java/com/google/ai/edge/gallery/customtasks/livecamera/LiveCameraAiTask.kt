package com.google.ai.edge.gallery.customtasks.livecamera

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

const val LIVE_CAMERA_AI_TASK_ID = "llm_live_camera_ai"

class LiveCameraAiTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = LIVE_CAMERA_AI_TASK_ID,
      label = "Live Camera AI",
      category = Category.LLM,
      icon = Icons.Outlined.Translate,
      description =
        "Keep the camera open and let AI continuously explain what it sees or translate visible text into Japanese.",
      shortDescription = "Live camera explanation",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery",
      models = mutableListOf(),
      modelNames =
        listOf(
          "Gemma-4-E2B-it",
          "Gemma-4-E4B-it",
          "Gemma-3n-E2B-it",
          "Gemma-3n-E4B-it",
        ),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      supportImage = true,
      supportAudio = false,
      onDone = onDone,
      coroutineScope = coroutineScope,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    LiveCameraAiScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      bottomPadding = myData.bottomPadding,
      setAppBarControlsDisabled = myData.setAppBarControlsDisabled,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object LiveCameraAiTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return LiveCameraAiTask()
  }
}
