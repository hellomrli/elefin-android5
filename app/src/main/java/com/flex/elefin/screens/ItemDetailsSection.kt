package com.flex.elefin.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.flex.elefin.jellyfin.JellyfinItem
import com.flex.elefin.jellyfin.JellyfinApiService
import com.flex.elefin.jellyfin.AppSettings

/**
 * Where an item's logo comes from: its own Logo image, or for an episode the series logo
 * (no tag - the server resolves it). Null when the item has no logo to show.
 */
internal fun logoSource(item: JellyfinItem): Pair<String, String?>? {
    item.ImageTags?.get("Logo")?.let { return item.Id to it }
    if (item.Type == "Episode" && item.SeriesId != null) return item.SeriesId to null
    return null
}

/**
 * Composable that displays either the title text or logo image based on settings
 */
@Composable
fun TitleOrLogo(
    item: JellyfinItem,
    apiService: JellyfinApiService?,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    logoHeightDp: Float = 45f, // Default height, can be customized per screen
    logoMaxWidthDp: Float? = null // Keeps very wide logos from running across the header
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val useLogo = settings.useLogoForTitle
    val logo = logoSource(item)
    
    // Track if logo failed to load; a new logo gets a fresh attempt
    var logoLoadFailed by remember(logo) { mutableStateOf(false) }
    
    if (useLogo && logo != null && apiService != null && !logoLoadFailed) {
        // Show logo image - size can be customized per screen
        // Default is 45dp, but can be reduced for specific screens (e.g., SeriesDetailsScreen uses 31.5dp)
        val logoHeight = logoHeightDp.dp
        // Fetch at display size: logos are transparent PNGs decoded as ARGB_8888, and the
        // unsized default (1920x1080) costs several MB per logo on a 1 GB box.
        val maxHeightPx = with(LocalDensity.current) { logoHeight.roundToPx() }
        
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(apiService.getImageUrl(logo.first, "Logo", logo.second, maxWidth = maxHeightPx * 4, maxHeight = maxHeightPx))
                    .headers(apiService.getImageRequestHeaders())
                    .build(),
                contentDescription = item.Name,
                modifier = Modifier
                    .height(logoHeight) // Fixed height for consistent layout
                    .then(if (logoMaxWidthDp != null) Modifier.widthIn(max = logoMaxWidthDp.dp) else Modifier)
                    .wrapContentWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { 
                    // Logo failed to load, fallback to title text
                    logoLoadFailed = true
                }
            )
        }
    } else {
        // Show title text
        Text(
            text = item.Name,
            style = style,
            color = color,
            modifier = modifier
        )
    }
}

/**
 * Reusable composable for displaying item title, metadata, and synopsis
 * Matching the style used on the home screen for uniformity
 */
@Composable
fun ItemDetailsSection(
    item: JellyfinItem,
    apiService: JellyfinApiService? = null,
    modifier: Modifier = Modifier,
    synopsisMaxLines: Int = 3,
    additionalMetadataContent: @Composable () -> Unit = {}
) {
    val runtimeText = formatRuntime(item.RunTimeTicks)
    val yearText = item.ProductionYear?.toString() ?: ""
    val genreText = item.Genres?.take(3)?.joinToString(", ") ?: ""
    
    Column(
        modifier = modifier
    ) {
        // Title or Logo
        TitleOrLogo(
            item = item,
            apiService = apiService,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.64f
            ),
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Metadata: Year, Runtime, Genre + Additional metadata boxes
        if (yearText.isNotEmpty() || runtimeText.isNotEmpty() || genreText.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                if (yearText.isNotEmpty()) {
                    Text(
                        text = yearText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.8f
                        ),
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
                if (runtimeText.isNotEmpty()) {
                    Text(
                        text = runtimeText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.8f
                        ),
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
                if (genreText.isNotEmpty()) {
                    Text(
                        text = genreText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.8f
                        ),
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
                
                // Add spacing before additional metadata boxes
                if (yearText.isNotEmpty() || runtimeText.isNotEmpty() || genreText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                // Additional metadata boxes (from play button row)
                additionalMetadataContent()
            }
        } else {
            // If no text metadata, still show additional metadata boxes
            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                additionalMetadataContent()
            }
        }
        
        // Synopsis
        item.Overview?.let { synopsis ->
            if (synopsis.isNotEmpty()) {
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
                        lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 0.8f * 1.1f // Reduced line spacing (10% of font size)
                    ),
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = synopsisMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
        
        // Director (only for movies)
        if (item.Type == "Movie") {
            val directors = item.People?.filter { it.Type == "Director" }?.mapNotNull { it.Name } ?: emptyList()
            if (directors.isNotEmpty()) {
                Text(
                    text = "Director: ${directors.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.8f
                    ),
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

