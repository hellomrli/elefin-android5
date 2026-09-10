package com.flex.elefin.jellyfin

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class JellyfinRepository(
    val apiService: JellyfinApiService,
    val settings: AppSettings
) {
    private val _continueWatchingItems = MutableStateFlow<List<JellyfinItem>>(emptyList())
    val continueWatchingItems: StateFlow<List<JellyfinItem>> = _continueWatchingItems.asStateFlow()

    private val _nextUpItems = MutableStateFlow<List<JellyfinItem>>(emptyList())
    val nextUpItems: StateFlow<List<JellyfinItem>> = _nextUpItems.asStateFlow()

    private val _recentlyAddedMovies = MutableStateFlow<List<JellyfinItem>>(emptyList())
    val recentlyAddedMovies: StateFlow<List<JellyfinItem>> = _recentlyAddedMovies.asStateFlow()
    
    // Movies per library - key is library ID, value is list of movies
    private val _recentlyAddedMoviesByLibrary = MutableStateFlow<Map<String, List<JellyfinItem>>>(emptyMap())
    val recentlyAddedMoviesByLibrary: StateFlow<Map<String, List<JellyfinItem>>> = _recentlyAddedMoviesByLibrary.asStateFlow()

    private val _recentlyReleasedMovies = MutableStateFlow<List<JellyfinItem>>(emptyList())
    val recentlyReleasedMovies: StateFlow<List<JellyfinItem>> = _recentlyReleasedMovies.asStateFlow()

    private val _recentlyAddedShows = MutableStateFlow<List<JellyfinItem>>(emptyList())
    val recentlyAddedShows: StateFlow<List<JellyfinItem>> = _recentlyAddedShows.asStateFlow()
    
    // Shows per library - key is library ID, value is list of shows
    private val _recentlyAddedShowsByLibrary = MutableStateFlow<Map<String, List<JellyfinItem>>>(emptyMap())
    val recentlyAddedShowsByLibrary: StateFlow<Map<String, List<JellyfinItem>>> = _recentlyAddedShowsByLibrary.asStateFlow()

    private val _recentlyAddedEpisodes = MutableStateFlow<List<JellyfinItem>>(emptyList())
    val recentlyAddedEpisodes: StateFlow<List<JellyfinItem>> = _recentlyAddedEpisodes.asStateFlow()
    
    // Episodes per library - key is library ID, value is list of episodes
    private val _recentlyAddedEpisodesByLibrary = MutableStateFlow<Map<String, List<JellyfinItem>>>(emptyMap())
    val recentlyAddedEpisodesByLibrary: StateFlow<Map<String, List<JellyfinItem>>> = _recentlyAddedEpisodesByLibrary.asStateFlow()

    private val _libraries = MutableStateFlow<List<JellyfinLibrary>>(emptyList())
    val libraries: StateFlow<List<JellyfinLibrary>> = _libraries.asStateFlow()
    
    private val _collections = MutableStateFlow<List<JellyfinItem>>(emptyList())
    val collections: StateFlow<List<JellyfinItem>> = _collections.asStateFlow()

    
    private val _collectionItems = MutableStateFlow<Map<String, List<JellyfinItem>>>(emptyMap())
    val collectionItems: StateFlow<Map<String, List<JellyfinItem>>> = _collectionItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val refreshMutex = Mutex()
    private var refreshVersion = 0L
    private var metadataRefreshedAt = 0L

    suspend fun refreshHome(forceMetadata: Boolean = false) {
        val versionAtRequest = refreshVersion
        refreshMutex.withLock {
            if (refreshVersion != versionAtRequest && !forceMetadata) return
            val refreshMetadata = forceMetadata || _libraries.value.isEmpty() ||
                System.currentTimeMillis() - metadataRefreshedAt >= 5 * 60_000L
            // Keep the load error visible; starting row requests would overwrite it with an empty state.
            if (refreshMetadata && !fetchLibraries()) return
            com.flex.elefin.player.PlaybackReports.awaitPendingReports()
            val tasks: List<suspend () -> Unit> = buildList {
                add { fetchContinueWatching() }
                add { fetchNextUp() }
                if (refreshMetadata) {
                    add { fetchCollections() }
                    add { fetchRecentlyAddedMovies() }
                    add { fetchRecentlyReleasedMovies() }
                    add { fetchRecentlyAddedShows() }
                    add { fetchRecentlyAddedEpisodes() }
                }
            }
            val permits = Semaphore(3)
            coroutineScope { tasks.map { task -> async { permits.withPermit { task() } } }.awaitAll() }
            if (refreshMetadata) metadataRefreshedAt = System.currentTimeMillis()
            refreshVersion++
        }
    }

    suspend fun fetchContinueWatching() {
        _isLoading.value = true
        _error.value = null
        try {
            val items = apiService.getContinueWatching(limit = settings.rowCardCount)
            _continueWatchingItems.value = items
            if (items.isEmpty()) {
                _error.value = "No continue watching items found"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            _error.value = "Error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun fetchNextUp() {
        try {
            val items = apiService.getNextUp(limit = settings.rowCardCount)
            _nextUpItems.value = items
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            _error.value = "fetchNextUp: ${e.message ?: e.javaClass.simpleName}"
            println("Error fetching next up items: ${e.message}")
        }
    }

    suspend fun fetchRecentlyAddedMovies() {
        try {
            // Fetch libraries if not already loaded
            val libraries = if (_libraries.value.isEmpty()) {
                try {
                    val fetchedLibraries = apiService.getLibraries()
                    _libraries.value = fetchedLibraries.filterNot { 
                        it.Type.equals("livetv", ignoreCase = true) || 
                        it.Name.equals("Live TV", ignoreCase = true)
                    }
                    _libraries.value
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyList()
                }
            } else {
                _libraries.value
            }
            
            // Fetch movies from each library separately and store per library
            val moviesByLibrary = mutableMapOf<String, List<JellyfinItem>>()
            val allItems = mutableListOf<JellyfinItem>()
            val seenIds = mutableSetOf<String>()
            
            // Fetch from each library that might contain movies
            libraries.filter { it.CollectionType.equals("movies", ignoreCase = true) }.forEach { library ->
                try {
                    // Fetch recently added items from this specific library
                    val libraryItems = apiService.getRecentlyAddedMoviesFromLibrary(library.Id, limit = settings.rowCardCount)
                    if (libraryItems.isNotEmpty()) {
                        // Sort by DateCreated descending and limit to settings.rowCardCount most recent
                        val sortedItems = libraryItems.sortedByDescending { 
                            it.DateCreated ?: ""
                        }.take(settings.rowCardCount)
                        moviesByLibrary[library.Id] = sortedItems
                        
                        // Also add to combined list for backward compatibility
                        sortedItems.forEach { item ->
                            if (seenIds.add(item.Id)) {
                                allItems.add(item)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Log but continue with other libraries
                    android.util.Log.w("JellyfinRepository", "Error fetching movies from library ${library.Name}: ${e.message}")
                }
            }
            
            // Store movies per library
            _recentlyAddedMoviesByLibrary.value = moviesByLibrary
            
            // Also store combined list (sorted and limited) for backward compatibility
            // Sort by DateCreated descending and limit to settings.rowCardCount most recent
            val sortedItems = allItems.sortedByDescending { 
                it.DateCreated ?: ""
            }.take(settings.rowCardCount)
            
            _recentlyAddedMovies.value = sortedItems
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            _error.value = "fetchRecentlyAddedMovies: ${e.message ?: e.javaClass.simpleName}"
            println("Error fetching recently added movies: ${e.message}")
        }
    }

    suspend fun fetchRecentlyReleasedMovies() {
        try {
            // First, get the default list (includes all accessible libraries)
            val defaultItems = apiService.getRecentlyReleasedMovies(limit = settings.rowCardCount)
            
            // Also fetch from all libraries individually to ensure we get items from all libraries
            // including "Movies 4K" or any other movie libraries
            // Fetch libraries if not already loaded
            val libraries = if (_libraries.value.isEmpty()) {
                try {
                    val fetchedLibraries = apiService.getLibraries()
                    _libraries.value = fetchedLibraries.filterNot { 
                        it.Type.equals("livetv", ignoreCase = true) || 
                        it.Name.equals("Live TV", ignoreCase = true)
                    }
                    _libraries.value
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyList()
                }
            } else {
                _libraries.value
            }
            
            val allItems = mutableListOf<JellyfinItem>()
            val seenIds = mutableSetOf<String>()
            
            // Add default items first
            defaultItems.forEach { item ->
                if (seenIds.add(item.Id)) {
                    allItems.add(item)
                }
            }
            
            // Fetch from each library that might contain movies
            libraries.filter { it.CollectionType.equals("movies", ignoreCase = true) }.forEach { library ->
                try {
                    // Fetch recently released items from this specific library
                    val libraryItems = apiService.getRecentlyReleasedMoviesFromLibrary(library.Id, limit = settings.rowCardCount)
                    libraryItems.forEach { item ->
                        if (seenIds.add(item.Id)) {
                            allItems.add(item)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Log but continue with other libraries
                    android.util.Log.w("JellyfinRepository", "Error fetching released movies from library ${library.Name}: ${e.message}")
                }
            }
            
            // Sort by PremiereDate descending and limit to settings.rowCardCount most recent
            val sortedItems = allItems.sortedByDescending { 
                it.PremiereDate ?: ""
            }.take(settings.rowCardCount)
            
            _recentlyReleasedMovies.value = sortedItems
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            _error.value = "fetchRecentlyReleasedMovies: ${e.message ?: e.javaClass.simpleName}"
            println("Error fetching recently released movies: ${e.message}")
        }
    }

    suspend fun fetchRecentlyAddedShows() {
        try {
            // Fetch libraries if not already loaded
            val libraries = if (_libraries.value.isEmpty()) {
                try {
                    val fetchedLibraries = apiService.getLibraries()
                    _libraries.value = fetchedLibraries.filterNot { 
                        it.Type.equals("livetv", ignoreCase = true) || 
                        it.Name.equals("Live TV", ignoreCase = true)
                    }
                    _libraries.value
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyList()
                }
            } else {
                _libraries.value
            }
            
            // Fetch shows from each library separately and store per library
            val showsByLibrary = mutableMapOf<String, List<JellyfinItem>>()
            val allItems = mutableListOf<JellyfinItem>()
            
            // Fetch from each library that might contain shows
            libraries.filter { it.CollectionType.equals("tvshows", ignoreCase = true) }.forEach { library ->
                try {
                    // Fetch recently added shows from this specific library
                    val libraryItems = apiService.getRecentlyAddedShowsFromLibrary(library.Id, limit = settings.rowCardCount)
                    if (libraryItems.isNotEmpty()) {
                        // Sort by DateCreated descending and limit to settings.rowCardCount most recent
                        val sortedItems = libraryItems.sortedByDescending { 
                            it.DateCreated ?: ""
                        }.take(settings.rowCardCount)
                        showsByLibrary[library.Id] = sortedItems
                        
                        // Also add to combined list for backward compatibility
                        sortedItems.forEach { item ->
                            allItems.add(item)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Log but continue with other libraries
                    android.util.Log.w("JellyfinRepository", "Error fetching shows from library ${library.Name}: ${e.message}")
                }
            }
            
            // Store shows per library
            _recentlyAddedShowsByLibrary.value = showsByLibrary
            
            // Also store combined list (sorted and limited) for backward compatibility
            val sortedItems = allItems.sortedByDescending { 
                it.DateCreated ?: ""
            }.take(settings.rowCardCount)
            
            _recentlyAddedShows.value = sortedItems
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            _error.value = "fetchRecentlyAddedShows: ${e.message ?: e.javaClass.simpleName}"
            println("Error fetching recently added shows: ${e.message}")
        }
    }

    suspend fun fetchRecentlyAddedEpisodes() {
        try {
            // Fetch libraries if not already loaded
            val libraries = if (_libraries.value.isEmpty()) {
                try {
                    val fetchedLibraries = apiService.getLibraries()
                    _libraries.value = fetchedLibraries.filterNot { 
                        it.Type.equals("livetv", ignoreCase = true) || 
                        it.Name.equals("Live TV", ignoreCase = true)
                    }
                    _libraries.value
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyList()
                }
            } else {
                _libraries.value
            }
            
            // Fetch episodes from each library separately and store per library
            val episodesByLibrary = mutableMapOf<String, List<JellyfinItem>>()
            val allItems = mutableListOf<JellyfinItem>()
            
            // Fetch from each library that might contain episodes
            libraries.filter { it.CollectionType.equals("tvshows", ignoreCase = true) }.forEach { library ->
                try {
                    // Fetch recently added episodes from this specific library
                    val libraryItems = apiService.getRecentlyAddedEpisodesFromLibrary(library.Id, limit = settings.rowCardCount)
                    if (libraryItems.isNotEmpty()) {
                        // Sort by DateCreated descending and limit to settings.rowCardCount most recent
                        val sortedItems = libraryItems.sortedByDescending { 
                            it.DateCreated ?: ""
                        }.take(settings.rowCardCount)
                        episodesByLibrary[library.Id] = sortedItems
                        
                        // Also add to combined list for backward compatibility
                        sortedItems.forEach { item ->
                            allItems.add(item)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Log but continue with other libraries
                    android.util.Log.w("JellyfinRepository", "Error fetching episodes from library ${library.Name}: ${e.message}")
                }
            }
            
            // Store episodes per library
            _recentlyAddedEpisodesByLibrary.value = episodesByLibrary
            
            // Also store combined list (sorted and limited) for backward compatibility
            val sortedItems = allItems.sortedByDescending { 
                it.DateCreated ?: ""
            }.take(settings.rowCardCount)
            
            _recentlyAddedEpisodes.value = sortedItems
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            _error.value = "fetchRecentlyAddedEpisodes: ${e.message ?: e.javaClass.simpleName}"
            println("Error fetching recently added episodes: ${e.message}")
        }
    }

    suspend fun fetchLibraries(): Boolean {
        _isLoading.value = true
        return try {
            val libraries = apiService.getLibraries()
            // Filter out Live TV library
            _libraries.value = libraries.filterNot { 
                it.Type.equals("livetv", ignoreCase = true) || 
                it.Name.equals("Live TV", ignoreCase = true)
            }
            _error.value = null
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val status = (e as? io.ktor.client.plugins.ResponseException)?.response?.status?.value
            _error.value = when (status) {
                401 -> "无法获取媒体库：登录已失效，请退出账号后重新登录。"
                403 -> "无法获取媒体库：当前账号没有访问权限，请检查服务器中的用户权限。"
                null -> "媒体库加载失败，请检查服务器连接后重试。"
                else -> "媒体库加载失败（HTTP " + status + "），请稍后重试。"
            }
            false
        } finally {
            _isLoading.value = false
        }
    }
    
    suspend fun fetchCollections() {
        try {
            val collections = apiService.getCollections()
            _collections.value = collections
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            _error.value = "fetchCollections: ${e.message ?: e.javaClass.simpleName}"
            println("Error fetching collections: ${e.message}")
        }
    }

    suspend fun fetchCollectionItems(collectionId: String) {
        try {
            // Fetch items from a collection (BoxSet)
            // Collections are BoxSets, so we fetch their children items
            val items = apiService.getLibraryItems(collectionId, limit = settings.rowCardCount).Items
            _collectionItems.update { currentMap ->
                currentMap.toMutableMap().apply {
                    put(collectionId, items)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            println("Error fetching collection items: ${e.message}")
        }
    }

    /**
     * Check if there is new media available by comparing recently added items.
     * Returns true if new media is detected.
     */
    suspend fun checkForNewMedia(): Boolean {
        return try {
            // Fetch a small sample of the most recent items (first 5 items)
            val recentMovies = apiService.getRecentlyAddedMovies(limit = 5)
            val recentEpisodes = apiService.getRecentlyAddedEpisodes(limit = 5)
            
            // Check if any of the recent items are not in our current lists
            val currentMovieIds = _recentlyAddedMovies.value.map { it.Id }.toSet()
            val currentEpisodeIds = _recentlyAddedEpisodes.value.map { it.Id }.toSet()
            
            val hasNewMovies = recentMovies.any { it.Id !in currentMovieIds }
            val hasNewEpisodes = recentEpisodes.any { it.Id !in currentEpisodeIds }
            
            // Also check if Continue Watching or Next Up changed
            val currentContinueWatchingIds = _continueWatchingItems.value.map { it.Id }.toSet()
            val currentNextUpIds = _nextUpItems.value.map { it.Id }.toSet()
            
            // Fetch fresh Continue Watching and Next Up to compare
            val freshContinueWatching = apiService.getContinueWatching()
            val freshNextUp = apiService.getNextUp()
            
            val hasNewContinueWatching = freshContinueWatching.map { it.Id }.toSet() != currentContinueWatchingIds
            val hasNewNextUp = freshNextUp.map { it.Id }.toSet() != currentNextUpIds
            
            hasNewMovies || hasNewEpisodes || hasNewContinueWatching || hasNewNextUp
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            false // On error, assume no new media to avoid unnecessary refreshes
        }
    }

    /**
     * Check for new media and refresh all rows if new content is detected.
     * Does NOT trigger a server-side library scan - only checks for media already detected by Jellyfin's backend tasks.
     * Used by automatic refresh mechanism.
     * Returns true if refresh was performed.
     */
    suspend fun checkForNewMediaAndRefresh(): Boolean {
        refreshHome(forceMetadata = true)
        return true
    }

    /**
     * Trigger a server-side library scan and refresh all media rows.
     * This forces the Jellyfin server to scan for new media immediately.
     * Used by manual refresh button.
     * Returns true if refresh was performed.
     */
    suspend fun triggerLibraryScanAndRefresh(): Boolean {
        // Trigger a library scan on the server to detect new media
        try {
            apiService.refreshLibrary()
            android.util.Log.d("JellyfinRepository", "Library scan triggered on server")
            
            // Wait a bit for the server to process the scan and generate images
            // Library scans are asynchronous, and images need time to be generated
            delay(2000) // Wait 2 seconds for scan to start and initial processing
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("JellyfinRepository", "Failed to trigger library scan, continuing with refresh anyway", e)
        }
        
        refreshHome(forceMetadata = true)
        return true
    }
}





