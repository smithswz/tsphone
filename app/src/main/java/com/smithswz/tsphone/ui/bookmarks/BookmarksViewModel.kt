package com.smithswz.tsphone.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smithswz.tsphone.data.db.BookmarkEntity
import com.smithswz.tsphone.data.prefs.IdentityState
import com.smithswz.tsphone.data.repo.BookmarkRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookmarksViewModel(
    private val repository: BookmarkRepository,
    identityState: kotlinx.coroutines.flow.StateFlow<IdentityState>
) : ViewModel() {

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        repository.allBookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val identity: StateFlow<IdentityState> = identityState

    fun add(name: String, address: String, port: Int, password: String?, nickname: String?) {
        viewModelScope.launch {
            repository.insert(
                BookmarkEntity(name = name, address = address, port = port, password = password, nickname = nickname)
            )
        }
    }

    fun update(bookmark: BookmarkEntity) {
        viewModelScope.launch { repository.update(bookmark) }
    }

    fun delete(bookmark: BookmarkEntity) {
        viewModelScope.launch { repository.delete(bookmark) }
    }
}
