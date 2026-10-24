package com.nuvio.app.features.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioToastHost
import com.nuvio.app.features.membership.CosmeticEntitlement
import com.nuvio.app.features.settings.MemberBrandWordmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: (NuvioProfile) -> Unit,
    onEditProfile: (NuvioProfile) -> Unit,
    onAddProfile: () -> Unit,
    onBack: (() -> Unit)? = null,
    interactionEnabled: Boolean = true,
    activeProfileIndex: Int? = null,
    contentVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pinDialogProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    var hoveredProfileIndex by remember { mutableStateOf<Int?>(null) }

    val titleAlpha = remember { Animatable(0f) }
    val titleOffset = remember { Animatable(20f) }
    val manageAlpha = remember { Animatable(0f) }
    val onProfileClick: (NuvioProfile) -> Unit = { profile ->
        if (interactionEnabled) {
            routeProfileSelection(
                profile = profile,
                isEditMode = isEditMode,
                activeProfileIndex = activeProfileIndex,
                onEditProfile = onEditProfile,
                onActiveProfileSelected = onProfileSelected,
                onPinRequired = { pinDialogProfile = it },
                onProfileSelected = onProfileSelected,
            )
        }
    }

    LaunchedEffect(Unit) {
        AvatarRepository.refreshAvatars()
    }

    LaunchedEffect(Unit) {
        launch { titleAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        launch { titleOffset.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
        manageAlpha.animateTo(1f, tween(220))
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backgroundProfile = profileState.activeProfile ?: profileState.profiles.firstOrNull()
    val hoveredProfileColor = remember(profileState.profiles, hoveredProfileIndex) {
        if (!isDesktop) {
            null
        } else {
            profileState.profiles
                .firstOrNull { it.profileIndex == hoveredProfileIndex }
                ?.avatarColorHex
                ?.let(::parseHexColor)
        }
    }

    LaunchedEffect(profileState.profiles) {
        if (hoveredProfileIndex != null && profileState.profiles.none { it.profileIndex == hoveredProfileIndex }) {
            hoveredProfileIndex = null
        }
    }

    fun updateHoveredProfile(profile: NuvioProfile, isHovered: Boolean) {
        hoveredProfileIndex = if (isHovered) {
            profile.profileIndex
        } else {
            hoveredProfileIndex.takeUnless { it == profile.profileIndex }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize(),
    ) {
        val isTabletLayout = maxWidth >= 768.dp
        Box(Modifier.fillMaxSize().background(Color.Black).drawWithCache {
            val netflixBackground = Brush.radialGradient(
                0.19f to Color(0xFF6F181D).copy(alpha = 0.8f),
                0.60f to Color(0xFF380C0F).copy(alpha = 0.4f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.5f, -size.height * 0.54f),
                radius = size.width * 1.25f,
            )
            onDrawBehind { drawRect(netflixBackground) }
        })
        MemberBrandWordmark(height = 24.dp, modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 24.dp))

        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = statusBarTop)
                    .then(
                        if (isTabletLayout) {
                            Modifier
                        } else {
                            Modifier.verticalScroll(rememberScrollState())
                        },
                    )
                    .padding(horizontal = 24.dp),
                verticalArrangement = if (isTabletLayout) Arrangement.Center else Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(if (isTabletLayout) 0.dp else 60.dp))


                Text(
                    text = stringResource(Res.string.profile_who_is_watching),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = if (isTabletLayout) 28.sp else 26.sp,
                        letterSpacing = 0.sp,
                    ),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer {
                        alpha = titleAlpha.value
                        translationY = titleOffset.value
                    },
                )

                Spacer(modifier = Modifier.height(if (isTabletLayout) 28.dp else 48.dp))

                val profiles = profileState.profiles
                val items = profiles.size + if (isEditMode && profiles.size < MAX_PROFILES) 1 else 0

                if (isTabletLayout) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            for (currentIndex in 0 until items) {
                                if (currentIndex < profiles.size) {
                                    val profile = profiles[currentIndex]
                                    ProfileAvatarCard(
                                        profile = profile,
                                        isEditMode = isEditMode,
                                        animDelay = currentIndex * 40,
                                        enabled = interactionEnabled,
                                        onHoverChange = { isHovered -> updateHoveredProfile(profile, isHovered) },
                                        onClick = {
                                            onProfileClick(profile)
                                        },
                                    )
                                } else {
                                    AddProfileCard(
                                        animDelay = currentIndex * 40,
                                        enabled = interactionEnabled,
                                        onClick = onAddProfile,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        var index = 0
                        while (index < items) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                for (col in 0..1) {
                                    if (index < items) {
                                        val currentIndex = index
                                        if (currentIndex < profiles.size) {
                                            val profile = profiles[currentIndex]
                                            ProfileAvatarCard(
                                                profile = profile,
                                                isEditMode = isEditMode,
                                                animDelay = currentIndex * 40,
                                                enabled = interactionEnabled,
                                                onHoverChange = { isHovered -> updateHoveredProfile(profile, isHovered) },
                                                onClick = {
                                                    onProfileClick(profile)
                                                },
                                            )
                                        } else {
                                            AddProfileCard(
                                                animDelay = currentIndex * 40,
                                                enabled = interactionEnabled,
                                                onClick = onAddProfile,
                                            )
                                        }
                                        index++
                                    } else {
                                        if (profiles.isNotEmpty()) {
                                            Spacer(modifier = Modifier.width(150.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isTabletLayout) 28.dp else 48.dp))

                Row(
                    modifier = Modifier.graphicsLayer { alpha = manageAlpha.value },
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (isEditMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent,
                            )
                            .border(
                                width = 1.dp,
                                color = if (isEditMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(22.dp),
                            )
                            .clickable(enabled = interactionEnabled) { isEditMode = !isEditMode }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (isEditMode) {
                                stringResource(Res.string.action_done)
                            } else {
                                stringResource(Res.string.profile_manage_profiles)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isEditMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (isTabletLayout) 0.dp else 32.dp))
            }
        }

        if (onBack != null && interactionEnabled && contentVisible) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = statusBarTop + 8.dp),
            )
        }

        NuvioToastHost(modifier = Modifier.align(Alignment.TopCenter))
    }

    pinDialogProfile?.let { profile ->
        PinEntryDialog(
            profileName = profile.name,
            onVerify = { pin -> ProfileRepository.verifyPin(profile.profileIndex, pin) },
            onVerified = {
                pinDialogProfile = null
                if (interactionEnabled && profile.profileIndex != activeProfileIndex) {
                    onProfileSelected(profile)
                }
            },
            onDismiss = { pinDialogProfile = null },
        )
    }
}

@Composable
private fun ProfileAvatarCard(
    profile: NuvioProfile,
    isEditMode: Boolean,
    animDelay: Int,
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val avatarSize = if (isDesktop) 108.dp else 96.dp
    val avatarShape = RoundedCornerShape(12.dp)
    val avatarColor = remember(profile.avatarColorHex) {
        parseHexColor(profile.avatarColorHex)
    }
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val avatarItem = remember(profile.avatarId, avatars) {
        profile.avatarId?.let { id -> avatars.find { it.id == id } }
    }
    val avatarImageUrl = remember(profile.avatarUrl, avatarItem) {
        profileAvatarImageUrl(profile, avatarItem)
    }

    val animAlpha = remember { Animatable(0f) }
    val animScale = remember { Animatable(0.85f) }
    val animOffset = remember { Animatable(30f) }

    LaunchedEffect(Unit) {
        delay(animDelay.toLong())
        launch { animAlpha.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { animScale.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
        launch { animOffset.animateTo(0f, tween(500, easing = FastOutSlowInEasing)) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val currentOnHoverChange = rememberUpdatedState(onHoverChange)
    val pressScale = if (isPressed) 0.95f else 1f

    LaunchedEffect(isHovered, profile.profileIndex) {
        if (isDesktop) {
            currentOnHoverChange.value(isHovered)
        }
    }

    DisposableEffect(profile.profileIndex) {
        onDispose {
            if (isDesktop) {
                currentOnHoverChange.value(false)
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(150.dp)
            .graphicsLayer {
                alpha = animAlpha.value
                scaleX = animScale.value * pressScale
                scaleY = animScale.value * pressScale
                translationY = animOffset.value
            }
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (isDesktop) {
                    Modifier.hoverable(interactionSource)
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier.size(avatarSize + 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarImageUrl != null) {
                val bgColor = avatarItem?.bgColor?.let { parseHexColor(it) } ?: avatarColor
                Box(
                    modifier = Modifier
                        .size(avatarSize + 8.dp)
                        .clip(avatarShape)
                        .background(bgColor.copy(alpha = 0.2f)),
                )
            }

            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .border(if (isHovered) 3.dp else 0.dp, if (isHovered) Color.White else Color.Transparent, avatarShape)
                    .clip(avatarShape)
                    .background(
                        if (avatarItem != null) {
                            avatarItem.bgColor?.let { parseHexColor(it) } ?: avatarColor
                        } else {
                            avatarColor.copy(alpha = 0.15f)
                        },
                    )
                    .then(
                        if (avatarImageUrl == null) Modifier.border(2.dp, avatarColor.copy(alpha = 0.4f), avatarShape)
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarImageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current).data(avatarImageUrl).size(Size.ORIGINAL).build(),
                        contentDescription = avatarItem?.displayName ?: profile.name,
                        modifier = Modifier.size(avatarSize).clip(avatarShape),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                    )
                } else if (profile.name.isNotBlank()) {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp),
                        color = avatarColor,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = avatarColor,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }

            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (profile.pinEnabled && !isEditMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile.name.ifBlank {
                stringResource(Res.string.profile_label_number, profile.profileIndex)
            },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = if (isHovered) Color.White else Color(0xFFB3B3B3),
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddProfileCard(
    animDelay: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val animAlpha = remember { Animatable(0f) }
    val animScale = remember { Animatable(0.85f) }
    val animOffset = remember { Animatable(30f) }

    LaunchedEffect(Unit) {
        delay(animDelay.toLong() + 150)
        launch { animAlpha.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { animScale.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
        launch { animOffset.animateTo(0f, tween(500, easing = FastOutSlowInEasing)) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale = if (isPressed) 0.95f else 1f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(150.dp)
            .graphicsLayer {
                alpha = animAlpha.value
                scaleX = animScale.value * pressScale
                scaleY = animScale.value * pressScale
                translationY = animOffset.value
            }
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(Res.string.compose_profile_add_profile),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
