package com.flex.elefin.jellyfin

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LibraryPagerTest {
    private fun movie(id: String) = JellyfinItem(id, "Movie $id", Type = MediaTypes.MOVIE)

    @Test fun firstPageDoesNotDownloadTheEntireLibrary() = runBlocking {
        val offsets = mutableListOf<Int>()
        val pager = LibraryPager(LibraryQuery("library")) { _, size, offset ->
            offsets.add(offset)
            ItemsResponse((offset until offset + size).map { movie(it.toString()) }, 10_000)
        }
        pager.loadNext()
        assertEquals(listOf(0), offsets)
        assertEquals(60, pager.state.value.items.size)
        assertFalse(pager.state.value.endReached)
        pager.loadNext()
        assertEquals(listOf(0, 60), offsets)
    }

    @Test fun seriesFilteringAndDuplicatesNeverChangeServerPageOffsets() = runBlocking {
        val offsets = mutableListOf<Int>()
        val series = JellyfinItem("series", "Series", Type = MediaTypes.SERIES, RecursiveItemCount = 5)
        val empty = JellyfinItem("empty", "Empty", Type = MediaTypes.SERIES, RecursiveItemCount = 0)
        val pager = LibraryPager(LibraryQuery("tv", types = MediaTypes.SERIES, hideEmptySeries = true), pageSize = 3) { _, _, offset ->
            offsets.add(offset)
            if (offset == 0) ItemsResponse(listOf(series, empty, series), 4)
            else ItemsResponse(listOf(series.copy(Id = "next")), 4)
        }
        pager.loadNext()
        assertEquals(listOf("series"), pager.state.value.items.map { it.Id })
        pager.loadNext()
        assertEquals(listOf(0, 3), offsets)
        assertEquals(listOf("series", "next"), pager.state.value.items.map { it.Id })
        assertTrue(pager.state.value.endReached)
    }

    @Test fun transientFailureKeepsLoadedItemsAndRetriesSameOffset() = runBlocking {
        var fail = false
        val offsets = mutableListOf<Int>()
        val pager = LibraryPager(LibraryQuery("library"), pageSize = 1) { _, _, offset ->
            offsets.add(offset)
            if (fail) error("offline")
            ItemsResponse(listOf(movie(offset.toString())), 3)
        }
        pager.loadNext()
        fail = true
        pager.loadNext()
        assertNotNull(pager.state.value.error)
        assertEquals(listOf("0"), pager.state.value.items.map { it.Id })
        fail = false
        pager.loadNext()
        assertEquals(listOf(0, 1, 1), offsets)
        assertEquals(2, pager.state.value.items.size)
    }

    @Test fun overlappingLoadTriggersFetchOnlyOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val pager = LibraryPager(LibraryQuery("library")) { _, _, _ ->
            calls++; entered.complete(Unit); release.await()
            ItemsResponse(listOf(movie("1")), 1)
        }
        val first = launch { pager.loadNext() }
        entered.await()
        pager.loadNext()
        release.complete(Unit)
        first.join()
        assertEquals(1, calls)
    }

    @Test fun backgroundCancellationDoesNotBecomeAnEmptySuccessfulPage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val pager = LibraryPager(LibraryQuery("library")) { _, _, _ -> entered.complete(Unit); awaitCancellation() }
        val job = launch { pager.loadNext() }
        entered.await()
        job.cancelAndJoin()
        assertFalse(pager.state.value.loading)
        assertFalse(pager.state.value.endReached)
        assertNull(pager.state.value.error)
        assertEquals(0, pager.state.value.nextIndex)
    }

    @Test fun changingGenreOrAlphabetRangeCannotReceiveOldQueryResults() = runBlocking {
        val releaseOld = CompletableDeferred<Unit>()
        val old = LibraryPager(LibraryQuery("library", genre = "Drama", nameFrom = "A")) { _, _, _ ->
            releaseOld.await(); ItemsResponse(listOf(movie("old")), 1)
        }
        val oldJob = launch { old.loadNext() }
        val current = LibraryPager(LibraryQuery("library", genre = "Comedy", nameFrom = "T")) { query, _, offset ->
            assertEquals("Comedy", query.genre); assertEquals("T", query.nameFrom); assertEquals(0, offset)
            ItemsResponse(listOf(movie("new")), 1)
        }
        current.loadNext()
        releaseOld.complete(Unit); oldJob.join()
        assertEquals(listOf("new"), current.state.value.items.map { it.Id })
    }
}
