package ru.nedotify.app.ui

import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.*
import ru.nedotify.app.ui.tokens.DesignTokens

val Blue:Color
    @Composable get()=MaterialTheme.colorScheme.primary
val Night=Color(DesignTokens.Dark.background)
val LocalGlass=staticCompositionLocalOf { true }
val LocalPowerSave=staticCompositionLocalOf { false }
val LocalOffline=staticCompositionLocalOf { false }
val LocalBackdrop=staticCompositionLocalOf<HazeState> { error("Missing backdrop") }

@Composable
fun NeDotifyTheme(dark:Boolean,accent:String="cyan",content:@Composable ()->Unit) {
    val (primary,onPrimary)=themeAccentColors(accent,dark)
    val colors=neDotifyColorScheme(dark,primary,onPrimary)
    fun type(size:Int,line:Int,weight:Int)=androidx.compose.ui.text.TextStyle(
        fontFamily=FontFamily.SansSerif,fontSize=size.sp,lineHeight=line.sp,fontWeight=FontWeight(weight))
    val body=type(DesignTokens.Typography.Body.size,DesignTokens.Typography.Body.lineHeight,DesignTokens.Typography.Body.weight)
    val small=type(DesignTokens.Typography.BodySmall.size,DesignTokens.Typography.BodySmall.lineHeight,DesignTokens.Typography.BodySmall.weight)
    val label=type(DesignTokens.Typography.Label.size,DesignTokens.Typography.Label.lineHeight,DesignTokens.Typography.Label.weight)
    val title=type(DesignTokens.Typography.Title.size,DesignTokens.Typography.Title.lineHeight,DesignTokens.Typography.Title.weight)
    val headline=type(DesignTokens.Typography.Headline.size,DesignTokens.Typography.Headline.lineHeight,DesignTokens.Typography.Headline.weight)
    MaterialTheme(colorScheme=colors,typography=Typography(
        headlineLarge=headline,headlineMedium=headline,headlineSmall=title,
        titleLarge=title,titleMedium=body.copy(fontWeight=FontWeight.SemiBold),titleSmall=small.copy(fontWeight=FontWeight.SemiBold),
        bodyLarge=body,bodyMedium=body,bodySmall=small,
        labelLarge=small.copy(fontWeight=FontWeight.Medium),labelMedium=label,labelSmall=label
    ),content=content)
}

/** Wallpaper-derived accent only; the app's neutral surfaces keep the reference's hierarchy. */
@Composable
fun themeAccentColors(name:String,dark:Boolean):Pair<Color,Color> {
    if(name=="system" && Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) {
        val context=LocalContext.current
        val scheme=if(dark)dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return scheme.primary to scheme.onPrimary
    }
    return accentColor(name,dark) to if(dark)Color(DesignTokens.Dark.onPrimary) else Color(DesignTokens.Light.onPrimary)
}

fun accentColor(name:String,dark:Boolean):Color=when(name) {
    "blue"->if(dark)Color(0xFF69ACFF) else Color(0xFF005AC4)
    "violet"->if(dark)Color(0xFFBDA1FF) else Color(0xFF6B42C8)
    "mint"->if(dark)Color(0xFF37D8BA) else Color(0xFF007563)
    else->if(dark)Color(DesignTokens.Dark.primary) else Color(DesignTokens.Light.primary)
}

@Composable
fun Modifier.glass(radius:Dp=DesignTokens.Radius.medium.dp,frosted:Boolean=false):Modifier {
    val colors=MaterialTheme.colorScheme
    val enabled=frosted && LocalGlass.current && Build.VERSION.SDK_INT>=31 && !LocalPowerSave.current
    val shape=RoundedCornerShape(radius)
    return this.clip(shape).then(if(enabled)Modifier.hazeEffect(LocalBackdrop.current,HazeStyle(
        backgroundColor=colors.surface,tint=HazeTint(colors.surface.copy(alpha=.78f)),blurRadius=14.dp,
        noiseFactor=0f,fallbackTint=HazeTint(colors.surface))) else Modifier.background(colors.surface))
        .border(1.dp,colors.outlineVariant.copy(alpha=.35f),shape)
}

/** One observer per app composition, rather than one receiver per glass row/card. */
@Composable
internal fun rememberPowerSaveMode():Boolean {
    val context=LocalContext.current.applicationContext
    val power=remember(context) {context.getSystemService(Context.POWER_SERVICE) as PowerManager}
    var saving by remember(power) {mutableStateOf(power.isPowerSaveMode)}
    DisposableEffect(context,power) {
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context,intent:Intent) {saving=power.isPowerSaveMode}
        }
        ContextCompat.registerReceiver(context,receiver,IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED)
        saving=power.isPowerSaveMode
        onDispose {context.unregisterReceiver(receiver)}
    }
    return saving
}

@Composable
fun NightSky(modifier:Modifier=Modifier,dark:Boolean=true,decorated:Boolean=true) {
    val background=MaterialTheme.colorScheme.background
    val accent=MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        drawRect(background)
        if(!decorated)return@Canvas
        val w=size.width;val h=size.height
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=if(dark).055f else .035f),Color.Transparent),
            Offset(w*.9f,h*.12f),w),w,Offset(w*.9f,h*.12f))
    }
}

@Composable
fun Cover(url:String,modifier:Modifier=Modifier,description:String?=null) {
    Box(modifier.clip(RoundedCornerShape(DesignTokens.Radius.small.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),contentAlignment=Alignment.Center) {
        Icon(Icons.Outlined.MusicNote,description,tint=Blue,modifier=Modifier.size(30.dp))
        val model=artworkModel(url,LocalOffline.current)
        if(model!=null)AsyncImage(model,description,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop,
            error=androidx.compose.ui.graphics.painter.ColorPainter(Color.Transparent))
    }
}
@Composable
fun PageTitle(title:String,subtitle:String) {
    Column(Modifier.padding(bottom=DesignTokens.Spacing.sm.dp)) {
        Text(title,style=MaterialTheme.typography.headlineMedium)
        Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
    }
}
@Composable
fun EmptyState(title:String,text:String,action:String?=null,onAction:()->Unit={}) {
    Column(Modifier.fillMaxWidth().glass().padding(DesignTokens.Spacing.xxl.dp),verticalArrangement=Arrangement.spacedBy(DesignTokens.Spacing.md.dp)) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center) {
            Icon(Icons.Outlined.MusicNote,null,tint=Blue,modifier=Modifier.size(28.dp))
        }
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(action!=null)Button(onAction){Text(action)}
    }
}
fun clock(ms:Long):String = "%d:%02d".format(java.util.Locale.ROOT,ms.coerceAtLeast(0)/60_000,ms.coerceAtLeast(0)/1000%60)

/** Every Material role is intentional: dialogs, chips and sliders share the app palette. */
internal fun neDotifyColorScheme(dark:Boolean,primary:Color,onPrimary:Color):ColorScheme {
    val surface=if(dark)Color(DesignTokens.Dark.surface) else Color(DesignTokens.Light.surface)
    val background=if(dark)Color(DesignTokens.Dark.background) else Color(DesignTokens.Light.background)
    val ink=if(dark)Color(DesignTokens.Dark.onSurface) else Color(DesignTokens.Light.onSurface)
    val muted=if(dark)Color(DesignTokens.Dark.onSurfaceVariant) else Color(DesignTokens.Light.onSurfaceVariant)
    val variant=if(dark)Color(DesignTokens.Dark.surfaceRaised) else Color(DesignTokens.Light.surfaceRaised)
    val container=primary.copy(alpha=if(dark).24f else .13f).compositeOver(surface)
    val secondary=if(dark)Color(0xFFA7CEE6) else Color(0xFF35617C)
    val tertiary=if(dark)Color(0xFFFF91C1) else Color(0xFF9C376B)
    val base=if(dark)darkColorScheme() else lightColorScheme()
    return base.copy(primary=primary,onPrimary=onPrimary,primaryContainer=container,onPrimaryContainer=ink,
        inversePrimary=accentColor("cyan",!dark),secondary=secondary,onSecondary=if(dark)Night else Color.White,
        secondaryContainer=secondary.copy(alpha=.2f).compositeOver(surface),onSecondaryContainer=ink,
        tertiary=tertiary,onTertiary=if(dark)Night else Color.White,
        tertiaryContainer=tertiary.copy(alpha=.2f).compositeOver(surface),onTertiaryContainer=ink,
        background=background,onBackground=ink,surface=surface,onSurface=ink,
        surfaceVariant=variant,onSurfaceVariant=muted,surfaceTint=primary,
        inverseSurface=if(dark)Color(0xFFE2EBF4) else Color(0xFF1C3042),inverseOnSurface=if(dark)Night else Color(0xFFF2F7FF),
        outline=if(dark)Color(DesignTokens.Dark.outline) else Color(DesignTokens.Light.outline),outlineVariant=if(dark)Color(DesignTokens.Dark.outline).copy(alpha=.55f) else Color(DesignTokens.Light.outline).copy(alpha=.35f),
        scrim=Color.Black,error=if(dark)Color(DesignTokens.Dark.error) else Color(DesignTokens.Light.error),
        onError=if(dark)Color(0xFF690005) else Color.White,errorContainer=if(dark)Color(0xFF93000A) else Color(0xFFFFDAD6),
        onErrorContainer=if(dark)Color(0xFFFFDAD6) else Color(0xFF410002),
        surfaceBright=variant,surfaceDim=background,
        surfaceContainerLowest=background,surfaceContainerLow=surface,
        surfaceContainer=surface,surfaceContainerHigh=variant,surfaceContainerHighest=variant)
}
