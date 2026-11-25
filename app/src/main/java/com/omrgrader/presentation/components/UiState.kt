package com.omrgrader.presentation.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<out T>(val data: T) : UiState<T>
    data class Empty(val message: String) : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
}

fun <T> Flow<T>.asUiState(
    scope: CoroutineScope,
    isEmpty: (T) -> Boolean = { false },
    emptyMessage: String = "No records yet"
): StateFlow<UiState<T>> = map<T, UiState<T>> { value ->
    if (isEmpty(value)) UiState.Empty(emptyMessage) else UiState.Success(value)
}.catch { emit(UiState.Error(it.javaClass.simpleName + ": " + (it.message ?: "load failed"))) }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)
