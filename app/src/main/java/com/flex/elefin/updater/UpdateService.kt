package com.flex.elefin.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Service for checking GitHub releases for app updates
 */
object UpdateService {
    private const val TAG = "UpdateService"
    
    private const val GITHUB_USERNAME = "hellomrli"
    private const val GITHUB_REPO = "elefin-android5"
    
    private val apiUrl = "https://api.github.com/repos/$GITHUB_USERNAME/$GITHUB_REPO/releases/latest"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // 5 minutes for large APK files
        .build()
    
    private val gson = Gson()
    
    /**
     * Fetches the latest release from GitHub
     * @return GitHubRelease if successful, null otherwise
     */
    suspend fun getLatestRelease(): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (!response.isSuccessful || body == null) {
                    Log.w(TAG, "Failed to fetch latest release: ${response.code}")
                    return@withContext null
                }
                
                val release = gson.fromJson(body, GitHubRelease::class.java)
                Log.d(TAG, "Fetched latest release: ${release.name} (${release.tagName})")
                release
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching latest release", e)
                null
            }
        }
    }
    
    /**
     * Parses version tag (e.g., "v1.0.5") to numeric version code (e.g., 10005)
     */
    fun parseVersion(tag: String): Int {
        return try {
            tag.replace("v", "", ignoreCase = true)
                .split(".")
                .let { parts ->
                    // Tags carry suffixes (e.g. "v1.2.0-zh-hw"); only the leading
                    // digits of each segment count ("0-zh-hw" -> 0).
                    fun String.leadingDigits(): Int =
                        takeWhile { ch -> ch.isDigit() }.ifEmpty { "0" }.toInt()
                    val major = parts.getOrNull(0)?.leadingDigits() ?: 0
                    val minor = parts.getOrNull(1)?.leadingDigits() ?: 0
                    val patch = parts.getOrNull(2)?.leadingDigits() ?: 0
                    major * 10000 + minor * 100 + patch
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing version tag: $tag", e)
            0
        }
    }
    
    /**
     * Checks if an update is available
     * @param remoteVersionCode Version code from GitHub release
     * @param localVersionCode Current app version code
     * @return true if remote version is newer
     */
    fun updateAvailable(remoteVersionCode: Int, localVersionCode: Int): Boolean {
        return remoteVersionCode > localVersionCode
    }

    /**
     * Picks the release asset that matches this device's CPU architecture.
     *
     * Releases ship one APK per ABI (elefin-release-arm64-v8a.apk,
     * elefin-release-armeabi-v7a.apk). Taking assets.first() would hand a 32-bit
     * box the arm64 build, which then fails to install.
     *
     * [Build.SUPPORTED_ABIS] is ordered most-preferred-first, so an arm64 device
     * gets the arm64 APK and falls back to armeabi-v7a if only that one is published.
     */
    fun selectApkAsset(assets: List<GitHubAsset>): GitHubAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        for (abi in Build.SUPPORTED_ABIS) {
            val match = apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (match != null) {
                Log.d(TAG, "Selected update asset ${match.name} for ABI $abi")
                return match
            }
        }

        // No per-ABI match: prefer an APK with no ABI in its name (a universal build),
        // otherwise just take the first and let the installer decide.
        val universal = apks.firstOrNull { asset ->
            Build.SUPPORTED_ABIS.none { asset.name.contains(it, ignoreCase = true) }
        }
        val fallback = universal ?: apks.first()
        Log.w(TAG, "No ABI-specific asset matched; falling back to ${fallback.name}")
        return fallback
    }
    
    /**
     * Downloads the APK file from the given URL and returns a File URI for installation
     * @param context Application context
     * @param apkUrl URL of the APK to download
     * @param progressCallback Optional callback for download progress (0-100) - will be called on Main dispatcher
     * @return File URI for the downloaded APK, or null if download failed
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        progressCallback: ((Int) -> Unit)? = null
    ): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting APK download from: $apkUrl")
                
                // Create download directory in app-specific external storage
                val downloadsDir = File(context.getExternalFilesDir(null), "updates")
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                // Create file name from URL
                val fileName = "elefin-update.apk"
                val apkFile = File(downloadsDir, fileName)
                
                // Delete old file if exists
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                
                // Download the file
                val request = Request.Builder()
                    .url(apkUrl)
                    .build()
                
                val response = downloadClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download APK: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body ?: run {
                    Log.e(TAG, "Response body is null")
                    return@withContext null
                }
                
                val contentLength = body.contentLength()
                val input = body.byteStream()
                val output = FileOutputStream(apkFile)
                
                try {
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    var lastProgress = -1
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Update progress if callback provided (on main thread for UI updates)
                        // Only update every 1% to avoid excessive callbacks
                        if (contentLength > 0 && progressCallback != null) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) {
                                    progressCallback(progress)
                                }
                            }
                        }
                    }
                    
                    // Ensure 100% is reported
                    if (contentLength > 0 && progressCallback != null) {
                        withContext(Dispatchers.Main) {
                            progressCallback(100)
                        }
                    }
                    
                    output.flush()
                    Log.d(TAG, "APK downloaded successfully: ${apkFile.absolutePath}")
                    
                    // Create FileProvider URI
                    val fileUri = FileProvider.getUriForFile(
                        context,
                        "com.flex.elefin.fileprovider",
                        apkFile
                    )
                    
                    Log.d(TAG, "APK URI created: $fileUri")
                    fileUri
                } finally {
                    input.close()
                    output.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading APK", e)
                null
            }
        }
    }
    
    /**
     * Attempts to install an APK file. First tries Intent.ACTION_VIEW, 
     * then falls back to PackageInstaller if needed.
     * @param context Application context
     * @param apkUri URI of the APK file to install
     * @return true if installation started successfully, false otherwise
     */
    suspend fun installApk(context: Context, apkUri: Uri): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Attempting to launch installer with URI: $apkUri")
                
                // Grant URI permissions to allow the installer to access the file
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.grantUriPermission(
                    "android",
                    apkUri,
                    flags
                )
                
                // Try Intent.ACTION_VIEW with the APK file.
                // NOTE: setType() clears any previously set data URI, so data and type
                // must be set together via setDataAndType() or the installer receives
                // an intent with no file attached.
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(flags)
                }
                
                // Try to find any activity that can handle this
                val resolveInfo = context.packageManager.queryIntentActivities(installIntent, 0)
                if (resolveInfo.isNotEmpty()) {
                    try {
                        context.startActivity(installIntent)
                        Log.d(TAG, "Installer activity launched successfully")
                        return@withContext true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start installer activity", e)
                    }
                }
                
                Log.e(TAG, "No installer activity found")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error launching installer", e)
                false
            }
        }
    }
}

