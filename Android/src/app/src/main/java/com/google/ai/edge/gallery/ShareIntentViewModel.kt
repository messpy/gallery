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

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds content shared to this app via Android's share intent (ACTION_SEND/ACTION_SEND_MULTIPLE). */
data class SharedContent(
  val text: String = "",
  val images: List<Bitmap> = emptyList(),
)

@HiltViewModel
class ShareIntentViewModel @Inject constructor() : ViewModel() {
  private val _sharedContent = MutableStateFlow<SharedContent?>(null)
  val sharedContent = _sharedContent.asStateFlow()

  fun setSharedContent(text: String, images: List<Bitmap>) {
    _sharedContent.update { SharedContent(text = text, images = images) }
  }

  fun consumeSharedContent() {
    _sharedContent.update { null }
  }
}
