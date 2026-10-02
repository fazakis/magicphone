// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MagicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xffB8E8CD), onPrimary = Color(0xff123D2F),
        primaryContainer = Color(0xff234C3F), onPrimaryContainer = Color(0xffD4F7E3),
        secondary = Color(0xffC2D2C4), secondaryContainer = Color(0xff2A3C33),
        onSecondaryContainer = Color(0xffDDEBDF), background = Color(0xff101914),
        surface = Color(0xff15201A), surfaceContainer = Color(0xff1E2B23),
        surfaceContainerLow = Color(0xff19241D), surfaceContainerHigh = Color(0xff28372D),
        onBackground = Color(0xffE8EEE8), onSurface = Color(0xffE8EEE8),
        onSurfaceVariant = Color(0xffA6B6AB), outlineVariant = Color(0xff33473A),
    ) else lightColorScheme(
        primary = Color(0xff234D3D), onPrimary = Color.White,
        primaryContainer = Color(0xffDCEDE0), onPrimaryContainer = Color(0xff1F4635),
        secondary = Color(0xff566D5D), secondaryContainer = Color(0xffE8EEDF),
        onSecondaryContainer = Color(0xff344D3C), background = Color(0xffF6F8F3),
        surface = Color(0xffFCFDF9), surfaceContainer = Color(0xffEDF2E9),
        surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xffE4EBDD),
        onBackground = Color(0xff1B2C23), onSurface = Color(0xff1B2C23),
        onSurfaceVariant = Color(0xff64746A), outlineVariant = Color(0xffDFE7DC),
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(32.dp),
    ), typography = Typography(
        headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-1).sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    ), content = content)
}

@Composable
fun AppIcon(resource: Int, modifier: Modifier = Modifier, description: String? = null) =
    Icon(painterResource(resource), contentDescription = description, modifier = modifier.size(22.dp))

@Composable
fun WelcomeTask(onExample: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Image(painterResource(R.drawable.ic_magic), null, Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)))
        Text(s(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge)
        Info(s(R.string.welcome_body))
        listOf(R.string.example_open, R.string.example_find).forEach { text ->
            val prompt = s(text)
            Surface(onClick = { onExample(prompt) }, color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    AppIcon(R.drawable.ic_arrow)
                }
            }
        }
    }
}
