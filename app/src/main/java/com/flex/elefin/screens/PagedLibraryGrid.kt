package com.flex.elefin.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.flex.elefin.jellyfin.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Shared, visibility-driven grid. Sorting, genres and A–Z seek operate on the full server library. */
@Composable
fun PagedLibraryGrid(
    api: JellyfinApiService?,
    libraryId: String,
    types: String = "Movie,Series",
    sort: SortType,
    genre: String? = null,
    hideEmptySeries: Boolean = false,
    lowPowerMode: Boolean,
    simpleCards: Boolean,
    googleTvCards: Boolean,
    refreshKey: Long = 0,
    modifier: Modifier = Modifier,
    onItemClick: (JellyfinItem, Long) -> Unit,
    onItemFocused: (JellyfinItem) -> Unit
) {
    if (api == null) return
    var letter by rememberSaveable(libraryId, sort, genre) { mutableStateOf<String?>(null) }
    var pendingLetter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingLetter) {
        if (pendingLetter != null) {
            delay(250) // Avoid a request for every key repeat while crossing the alphabet bar.
            letter = pendingLetter.takeUnless { it == "#" }
        }
    }
    val query = remember(libraryId, types, sort, genre, letter, hideEmptySeries) {
        LibraryQuery(
            parentId = libraryId, types = types,
            sort = when (sort) {
                SortType.Alphabetically -> LibrarySort.TITLE
                SortType.DateAdded -> LibrarySort.DATE_ADDED
                SortType.DateReleased -> LibrarySort.DATE_RELEASED
            },
            genre = genre, nameFrom = letter.takeIf { sort == SortType.Alphabetically },
            hideEmptySeries = hideEmptySeries
        )
    }
    val pager = remember(api, query, refreshKey) { LibraryPager(query, fetch = api::getLibraryPage) }
    val page by pager.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val columns = 6

    LaunchedEffect(pager, lifecycle) {
        listState.scrollToItem(0)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (pager.state.value.nextIndex == 0) pager.loadNext()
            snapshotFlow {
                Triple(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, page.items.size, page.nextIndex)
            }.distinctUntilChanged().collect { (lastVisible, count, _) ->
                if (lastVisible >= (count + columns - 1) / columns - 3 && page.error == null) pager.loadNext()
            }
        }
    }

    Row(modifier.fillMaxSize()) {
        if (sort == SortType.Alphabetically) {
            LazyColumn(
                modifier = Modifier.width(46.dp).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items((listOf("#") + ('A'..'Z').map { it.toString() }), key = { it }) { value ->
                    Text(value, color = Color.White, modifier = Modifier
                        .fillMaxWidth().background(if (value == letter) Color.DarkGray else Color.Transparent)
                        .onFocusChanged { if (it.isFocused) pendingLetter = value }
                        .clickable { pendingLetter = value }
                        .padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(focusRequester),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)
        ) {
            items(page.items.chunked(columns), key = { it.first().Id }, contentType = { "library_row" }) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                    row.forEach { item ->
                        key(item.Id) {
                            Column(Modifier.width(105.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                JellyfinHorizontalCard(
                                    item = item, apiService = api,
                                    onClick = { onItemClick(item, 0) },
                                    onFocusChanged = { if (it) onItemFocused(item) },
                                    useSimpleCards = simpleCards, useGoogleTvCards = googleTvCards, lowPowerMode = lowPowerMode
                                )
                                if (!lowPowerMode) Text(item.Name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.width(105.dp)) }
                }
            }
            item(key = "page_status", contentType = "page_status") {
                when {
                    page.error != null -> Button(onClick = { scope.launch { pager.loadNext() } }) { Text("加载失败，重试") }
                    page.loading -> Text("正在加载…", modifier = Modifier.padding(16.dp))
                    page.endReached && page.items.isEmpty() -> Text("没有符合条件的内容", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
