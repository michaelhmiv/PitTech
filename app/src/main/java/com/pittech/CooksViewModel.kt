package com.pittech

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pittech.data.CookRepository
import com.pittech.data.CookWithDishes
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CooksViewModel(private val repository: CookRepository) : ViewModel() {
    val cooks: StateFlow<List<CookWithDishes>> = repository.observeCooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _savedCookId = MutableStateFlow<String?>(null)
    val savedCookId: StateFlow<String?> = _savedCookId.asStateFlow()

    fun startCook(draft: NewCookDraft) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            _saveError.value = null
            try {
                _savedCookId.value = repository.startCook(draft)
            } catch (failure: Exception) {
                _saveError.value = failure.message ?: "The cook could not be saved. Try again."
            } finally {
                _saving.value = false
            }
        }
    }

    fun clearSavedCookSignal() {
        _savedCookId.value = null
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    class Factory(private val repository: CookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CooksViewModel::class.java))
            return CooksViewModel(repository) as T
        }
    }
}
