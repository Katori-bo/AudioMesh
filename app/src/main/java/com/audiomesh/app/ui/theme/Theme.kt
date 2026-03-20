package com.audiomesh.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

// ── Role badge colors ─────────────────────────────────────────────────────────
// These are fixed regardless of album palette — role = color, always.

val RoleFull        = Color(0xFF7C3AED)   // purple
val RoleFullSurface = Color(0xFF3B0764)
val RoleFullOn      = Color(0xFFC4B5FD)

val RoleBass        = Color(0xFFC2410C)   // orange
val RoleBassSurface = Color(0xFF431407)
val RoleBassOn      = Color(0xFFFDBA74)

val RoleTreble        = Color(0xFF1D4ED8) // blue
val RoleTrebleSurface = Color(0xFF1E3A5F)
val RoleTrebleOn      = Color(0xFF93C5FD)

val RoleMid        = Color(0xFF166534)    // green
val RoleMidSurface = Color(0xFF052E16)
val RoleMidOn      = Color(0xFF86EFAC)

// ── Static dark scheme — used when dynamic color unavailable ──────────────────
// Primary surface is near-black, not pure black — easier on the eyes.

private val AudioMeshDarkScheme = darkColorScheme(
    primary          = Color(0xFFFFFFFF),
    onPrimary        = Color(0xFF111111),
    primaryContainer = Color(0xFF1F1F1F),
    secondary        = Color(0xFFAAAAAA),
    onSecondary      = Color(0xFF111111),
    background       = Color(0xFF0D0D0D),
    onBackground     = Color(0xFFFFFFFF),
    surface          = Color(0xFF161616),
    onSurface        = Color(0xFFFFFFFF),
    surfaceVariant   = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline          = Color(0xFF2A2A2A),
)

// ── Light scheme — kept minimal, app is dark-first ───────────────────────────

private val AudioMeshLightScheme = lightColorScheme(
    primary          = Color(0xFF111111),
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0F0F0),
    secondary        = Color(0xFF555555),
    onSecondary      = Color(0xFFFFFFFF),
    background       = Color(0xFFFAFAFA),
    onBackground     = Color(0xFF111111),
    surface          = Color(0xFFFFFFFF),
    onSurface        = Color(0xFF111111),
    surfaceVariant   = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF555555),
    outline          = Color(0xFFDDDDDD),
)

// ── Dynamic player palette ────────────────────────────────────────────────────
// Extracted from album art at runtime. Passed down through the composition tree.
// Screens that need it read LocalPlayerPalette.current.

data class PlayerPalette(
    val dominant  : Color = Color(0xFF1F1F1F),
    val vibrant   : Color = Color(0xFF444444),
    val muted     : Color = Color(0xFF2A2A2A),
    val onDominant: Color = Color(0xFFFFFFFF),
)

val LocalPlayerPalette = staticCompositionLocalOf { PlayerPalette() }

// ── Root theme composable ─────────────────────────────────────────────────────

@Composable
fun AudioMeshTheme(
    darkTheme    : Boolean = true,   // AudioMesh defaults to dark
    playerPalette: PlayerPalette = PlayerPalette(),
    content      : @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else           dynamicLightColorScheme(context)
        }
        darkTheme -> AudioMeshDarkScheme
        else      -> AudioMeshLightScheme
    }

    CompositionLocalProvider(
        LocalPlayerPalette provides playerPalette
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = AudioMeshTypography,
            content     = content
        )
    }
}