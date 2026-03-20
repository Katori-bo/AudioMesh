package com.audiomesh.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AudioMeshTypography = Typography(

    // AUDIOMESH app name, screen titles
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize   = 28.sp,
        letterSpacing = 6.sp,          // wide tracking = all-caps feel
    ),

    // Track title on now playing screen
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize   = 24.sp,
        letterSpacing = (-0.5).sp,     // tight tracking for large titles
    ),

    // Artist name, album title
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        letterSpacing = 0.sp,
    ),

    // Song rows in library, device names in sheet
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 15.sp,
        letterSpacing = 0.sp,
    ),

    // Secondary info — artist in library row, ping ms, timestamps
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 13.sp,
        letterSpacing = 0.sp,
    ),

    // Role badges, section labels, caps labels
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize   = 10.sp,
        letterSpacing = 1.5.sp,        // wide tracking for small caps labels
    ),

    // Sync drift values, monospace-feel numbers
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
        letterSpacing = 0.5.sp,
    ),
)