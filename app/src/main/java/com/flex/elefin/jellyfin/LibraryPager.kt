package com.flex.elefin.jellyfin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

// Jellyfin ItemSortBy has no "Id" value. Use supported secondary sort fields.
enum class LibrarySort(val apiValue: String, val descending: Boolean) {
    TITLE("SortName,ProductionYear,DateCreated", false),
    DATE_ADDED("DateCreated,SortName,ProductionYear", true),
    DATE_RELEASED("PremiereDate,SortName,DateCreated", true)
}

data class LibraryQuery(
    val parentId: String,
    val types: String = "Movie,Series",
    val sort: LibrarySort = LibrarySort.TITLE,
    val genre: String? = null,
    val nameFrom: String? = null,
    val hideEmptySeries: Boolean = false
) {
    val sortBy: String get() = sort.apiValue
    val descending: Boolean get() = sort.descending
}

data class LibraryPageState(
    val items: List<JellyfinItem> = emptyList(),
    val nextIndex: Int = 0,
    val total: Int? = null,
    val endReached: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
)

/** One query owns one pager. Cancelling/replacing a query cannot publish into a newer pager. */
class LibraryPager(
    val query: LibraryQuery,
    private val pageSize: Int = 60,
    private val fetch: suspend (LibraryQuery, Int, Int) -> ItemsResponse
) {
    private val mutableState = MutableStateFlow(LibraryPageState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    suspend fun loadNext() {
        // Collapse overlapping visibility/retry triggers for the same page.
        if (!mutex.tryLock()) return
        try {
            val before = mutableState.value
            if (before.endReached) return
            mutableState.value = before.copy(loading = true, error = null)
            try {
                val response = fetch(query, pageSize, before.nextIndex)
                val page = response.Items.filter {
                    it.Type in query.types.split(',') && (!query.hideEmptySeries || it.Type != MediaTypes.SERIES ||
                        (it.RecursiveItemCount ?: it.ChildCount ?: 1) > 0)
                }
                // Advance by raw server rows, not by filtered/unique display rows.
                val next = before.nextIndex + response.Items.size
                val total = response.TotalRecordCount.takeIf { it > 0 }
                mutableState.value = LibraryPageState(
                    items = (before.items + page).distinctBy { it.Id },
                    nextIndex = next,
                    total = total,
                    endReached = response.Items.isEmpty() || (total != null && next >= total) ||
                        (total == null && response.Items.size < pageSize)
                )
            } catch (cancelled: CancellationException) {
                mutableState.value = before
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = before.copy(error = "加载失败，请重试")
            }
        } finally {
            mutex.unlock()
        }
    }
}
