package com.dragosfotache.gymtracka.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.dragosfotache.gymtracka.data.gym.GymRepository

internal val LocalGymRepositoryOverride = staticCompositionLocalOf<GymRepository?> { null }

@Composable
internal fun rememberGymRepository(): GymRepository {
    val override = LocalGymRepositoryOverride.current
    val context = LocalContext.current.applicationContext
    return override ?: remember(context) { GymRepository(context) }
}
