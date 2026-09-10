package com.flex.elefin.jellyfin

import com.flex.elefin.music.data.JellyfinMusicApi
import com.flex.elefin.player.mpv.MpvElefinLauncher
import com.flex.elefin.player.mpv.MpvUrlBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class Jellyfin12CompatibilityTest {
    private val baseUrl = "https://server.example/jellyfin"
    private val token = "test-access-token"
    private val userId = "00000000000000000000000000000001"
    private val libraryId = "00000000000000000000000000000002"
    private val itemId = "00000000000000000000000000000003"
    private val itemJson = """{
        "Id":"$itemId","Name":"测试影片","Type":"Movie",
        "UserData":{"PlaybackPositionTicks":123000000,"Played":false},
        "ImageTags":{"Primary":"image-tag"},"ServerId":"ignored-server-field"
    }"""
    private val itemsJson = """{"Items":[$itemJson],"TotalRecordCount":1,"StartIndex":0}"""

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ) = HttpClient(MockEngine(handler)) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun api(client: HttpClient) = JellyfinApiService(baseUrl, token, userId, client = client)

    private fun parseAuthorization(value: String): Map<String, String> {
        assertTrue(value.startsWith("MediaBrowser "))
        return Regex("""(\w+)="([^"]*)"""").findAll(value).associate {
            it.groupValues[1] to URLDecoder.decode(it.groupValues[2], "UTF-8")
        }
    }

    /** Model 12.0's default: legacy headers and api_key do not authenticate a request. */
    private fun assertModernAuthorization(request: HttpRequestData, expectedToken: String? = token) {
        assertNull(request.headers["X-Emby-Authorization"])
        assertNull(request.headers["X-Emby-Token"])
        assertNull(request.headers["X-MediaBrowser-Token"])
        assertNull(request.url.parameters["api_key"])
        val values = request.headers.getAll("Authorization")
        assertNotNull(values)
        assertEquals(1, values!!.size)
        val parameters = parseAuthorization(values.single())
        assertEquals("Elefin", parameters["Client"])
        assertEquals(expectedToken, parameters["Token"])
    }

    @Test fun authorizationParametersRoundTripWithoutSplittingOrInjectingHeaders() {
        val name = "电视, \"客厅\" + 100%"
        val id = "device+id"
        val header = JellyfinAuthorization.header(token, id, deviceName = name)
        val parts = parseAuthorization(header)
        assertEquals(5, parts.size)
        assertEquals(name, parts["Device"])
        assertEquals(id, parts["DeviceId"])
        assertEquals(token, parts["Token"])
        val untrusted = JellyfinAuthorization.header(deviceName = "TV\r\nInjected: value")
        assertFalse(untrusted.contains('\r'))
        assertFalse(untrusted.contains('\n'))
    }

    @Test fun passwordLoginUsesTheStandardHeaderAndKeepsThePasswordInTheBody() = runBlocking {
        client { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/jellyfin/Users/authenticatebyname", request.url.encodedPath)
            assertModernAuthorization(request, expectedToken = null)
            assertEquals("android-tv-device", parseAuthorization(request.headers["Authorization"]!!)["DeviceId"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("test-user", body["Username"]!!.jsonPrimitive.content)
            assertEquals("test-password", body["Pw"]!!.jsonPrimitive.content)
            respond("""{"AccessToken":"$token","User":{"Id":"$userId","Name":"test-user"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { client ->
            val result = JellyfinAuthService(baseUrl, client = client).authenticate("test-user", "test-password")
            assertEquals(token, result?.AccessToken)
            assertEquals(userId, result?.User?.Id)
        }
    }

    @Test fun quickConnectUsesPostInitiationAndStandardHeadersThroughout() = runBlocking {
        val requests = mutableListOf<String>()
        client { request ->
            assertModernAuthorization(request, expectedToken = null)
            val path = request.url.encodedPath
            requests.add(path)
            val body = when (path) {
                "/jellyfin/QuickConnect/Initiate" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    """{"Secret":"test-secret","Code":"123456"}"""
                }
                "/jellyfin/QuickConnect/Connect" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("test-secret", request.url.parameters["secret"])
                    """{"Authenticated":true,"Code":"123456"}"""
                }
                "/jellyfin/Users/authenticateWithQuickConnect" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("test-secret", payload["Secret"]!!.jsonPrimitive.content)
                    """{"AccessToken":"$token","User":{"Id":"$userId","Name":"test-user"}}"""
                }
                else -> error("Unexpected Quick Connect route")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { client ->
            val service = QuickConnectService(baseUrl, client = client)
            assertEquals("123456", service.initiateQuickConnect().data?.Code)
            assertEquals(true, service.getQuickConnectState("test-secret")?.Authenticated)
            assertEquals(token, service.authenticateWithQuickConnect("test-secret")?.AccessToken)
            assertEquals(3, requests.size)
        }
    }

    @Test fun browsingSearchDetailsAndResumeWorkWithoutLegacyAuthorization() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        client { request ->
            assertModernAuthorization(request)
            assertEquals(HttpMethod.Get, request.method)
            assertTrue(request.url.encodedPath.startsWith("/jellyfin/"))
            requests.add(request)
            val body = when (request.url.encodedPath) {
                "/jellyfin/Users/$userId/Views" ->
                    """{"Items":[{"Id":"$libraryId","Name":"电影","CollectionType":"movies","Type":"CollectionFolder"}]}"""
                "/jellyfin/Items/$itemId" -> itemJson
                else -> itemsJson
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { client ->
            val api = api(client)
            assertEquals(libraryId, api.getLibraries().single().Id)
            val page = api.getLibraryPage(LibraryQuery(parentId = libraryId, types = "Movie"), startIndex = 60)
            assertEquals(itemId, page.Items.single().Id)
            assertEquals(1, page.TotalRecordCount)
            assertEquals("60", requests.last().url.parameters["StartIndex"])
            assertEquals(libraryId, requests.last().url.parameters["ParentId"])
            assertEquals(itemId, api.getRecentlyAddedMovies().single().Id)
            assertEquals(itemId, api.getContinueWatching().single().Id)
            assertEquals(itemId, api.getNextUp().single().Id)
            assertEquals(itemId, api.searchItems("测试").single().Id)
            assertEquals(123000000L, api.getItemDetails(itemId)?.UserData?.PositionTicks)
            assertEquals(7, requests.size)
        }
    }

    @Test fun failedLibraryRequestsPropagateInsteadOfReturningAnEmptyLibrary() = runBlocking {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            client { respond("request rejected", status) }.use { client ->
                try {
                    api(client).getLibraries()
                    fail("A failed request must not be presented as an empty library")
                } catch (error: ResponseException) {
                    assertEquals(status, error.response.status)
                }
            }
        }
        client { respond("""{"Items":[],"TotalRecordCount":0}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json")) }.use { client ->
            assertTrue(api(client).getLibraries().isEmpty())
        }
    }

    @Test fun cancellingLibraryLoadingStillCancelsTheRequest() = runBlocking {
        client { throw CancellationException("screen closed") }.use { client ->
            try {
                api(client).getLibraries()
                fail("Cancellation must propagate")
            } catch (expected: CancellationException) {
                assertEquals("screen closed", expected.message)
            }
        }
    }

    @Test fun playbackAndWatchedReportsKeepTheTokenWhenDeviceIdIsMissing() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        client { request ->
            assertModernAuthorization(request)
            requests.add(request)
            respond("", HttpStatusCode.NoContent)
        }.use { client ->
            val api = api(client)
            assertTrue(api.reportPlaybackStart(itemId))
            assertTrue(api.reportPlaybackProgress(itemId, 123000000))
            assertTrue(api.reportPlaybackStopped(itemId, 123000000))
            assertTrue(api.markAsWatched(itemId))
            assertTrue(api.markAsUnwatched(itemId))
            assertEquals(5, requests.size)
            assertEquals(HttpMethod.Delete, requests.last().method)
            assertTrue(requests.take(4).all { it.method == HttpMethod.Post })
        }
    }

    @Test fun playbackNegotiationAndImageHeadersUseModernAuthorization() = runBlocking {
        client { request ->
            assertModernAuthorization(request)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/jellyfin/Items/$itemId/PlaybackInfo", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertNotNull(body["DeviceProfile"])
            respond("""{"MediaSources":[{"Id":"$itemId","Container":"mkv"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { client ->
            val api = api(client)
            assertEquals(itemId, api.getPlaybackInfo(itemId, itemId)?.MediaSources?.single()?.Id)
            val imageHeaders = api.getImageRequestHeaders()
            assertEquals(setOf("Authorization"), imageHeaders.names())
            assertEquals(token, parseAuthorization(imageHeaders["Authorization"]!!)["Token"])
            assertEquals(token, parseAuthorization(api.getVideoRequestHeaders().getValue("Authorization"))["Token"])
        }
    }

    @Test fun videoMpvAndSubtitleUrlsUseApiKeyInsteadOfTheDisabledUnderscoreParameter() {
        client { error("URL construction must not make a request") }.use { client ->
            val api = api(client)
            val urls = listOf(
                api.getVideoPlaybackUrl(itemId),
                api.getTranscodedVideoUrl(itemId, itemId),
                api.buildSubtitleUrl(itemId, itemId, 2),
                api.buildJellyfinSubtitleUrl(itemId, itemId, 2, true, "srt"),
                MpvUrlBuilder.buildStreamUrl(baseUrl, itemId, token),
                MpvUrlBuilder.buildDownloadUrl(baseUrl, itemId, token),
                MpvElefinLauncher.buildStreamUrl(baseUrl, itemId, token)
            )
            for (url in urls) {
                val parsed = Url(url)
                assertEquals(token, parsed.parameters["ApiKey"])
                assertNull(parsed.parameters["api_key"])
                assertTrue(parsed.encodedPath.startsWith("/jellyfin/"))
            }
            val mpvHeaders = MpvUrlBuilder.buildHeaders(token, "device-id")
            val authLine = mpvHeaders.lineSequence().single { it.startsWith("Authorization: ") }
            assertEquals(token, parseAuthorization(authLine.removePrefix("Authorization: "))["Token"])
            assertFalse(mpvHeaders.contains("X-Emby"))
            assertEquals(mpvHeaders, MpvElefinLauncher.buildHeaders(token, "device-id"))
        }
    }

    @Test fun musicBrowsingAndHeaderlessMusicUrlsUseSupportedAuthentication() = runBlocking {
        client { request ->
            assertModernAuthorization(request)
            val types = request.url.parameters["IncludeItemTypes"]
            assertTrue(types == "MusicArtist" || types == "Audio")
            respond(itemsJson, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.use { client ->
            val music = JellyfinMusicApi(baseUrl, token, userId, client)
            val artist = music.getArtists().single()
            assertEquals(token, Url(artist.imageUrl!!).parameters["ApiKey"])
            val track = music.getTracksForAlbum(libraryId).single()
            assertEquals(token, Url(track.streamUrl).parameters["ApiKey"])
            assertNull(Url(track.streamUrl).parameters["api_key"])
        }
    }
}
