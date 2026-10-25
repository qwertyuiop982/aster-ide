package dev.aster.ide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.aster.core.WorkMode

/** Stores survive Activity recreation, but are released on an explicit work-mode switch. */
class ModeViewModels : ViewModel() {
    private val owners = mutableMapOf<WorkMode, ViewModelStoreOwner>()
    fun owner(mode: WorkMode): ViewModelStoreOwner = owners.getOrPut(mode) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    fun release(mode: WorkMode?) { owners.remove(mode)?.viewModelStore?.clear() }
    override fun onCleared() { owners.values.forEach { it.viewModelStore.clear() }; owners.clear() }
}
