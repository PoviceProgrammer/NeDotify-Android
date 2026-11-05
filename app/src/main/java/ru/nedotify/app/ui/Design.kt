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

val Blue:Color
    @Composable get()=MaterialTheme.colorScheme.primary
val Night=Color(0xFF0A0E17)
val LocalGlass=staticCompositionLocalOf { true }
val LocalPowerSave=staticCompositionLocalOf { false }
val LocalOffline=staticCompositionLocalOf { false }
val LocalBackdrop=staticCompositionLocalOf<HazeState> { error("Missing backdrop") }

@Composable
fun NeDotifyTheme(dark:Boolean,accent:String="cyan",content:@Composable ()->Unit) {
    val (primary,onPrimary)=themeAccentColors(accent,dark)
    val colors=neDotifyColorScheme(dark,primary,onPrimary)
    MaterialTheme(colorScheme=colors,typography=Typography(
        headlineLarge=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontSize=30.sp,fontWeight=FontWeight.Bold,lineHeight=36.sp),
        headlineMedium=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontSize=25.sp,fontWeight=FontWeight.Bold,lineHeight=31.sp),
        titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontSize=20.sp,fontWeight=FontWeight.SemiBold,lineHeight=26.sp)
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
    return accentColor(name,dark) to if(dark)Color(0xFF001C2D) else Color.White
}

fun accentColor(name:String,dark:Boolean):Color=when(name) {
    "blue"->if(dark)Color(0xFF69ACFF) else Color(0xFF005AC4)
    "violet"->if(dark)Color(0xFFBDA1FF) else Color(0xFF6B42C8)
    "mint"->if(dark)Color(0xFF37D8BA) else Color(0xFF007563)
    else->if(dark)Color(0xFF00B6F5) else Color(0xFF006A94)
}

@Composable
fun Modifier.glass(radius:Dp=18.dp):Modifier {
    val colors=MaterialTheme.colorScheme
    val enabled=LocalGlass.current && Build.VERSION.SDK_INT>=31 && !LocalPowerSave.current
    val shape=RoundedCornerShape(radius)
    return this.clip(shape).then(if(enabled)Modifier.hazeEffect(LocalBackdrop.current,HazeStyle(
        backgroundColor=colors.surface,tint=HazeTint(colors.surface.copy(alpha=.78f)),blurRadius=14.dp,
        noiseFactor=0f,fallbackTint=HazeTint(colors.surface))) else Modifier.background(colors.surface))
        .border(1.dp,colors.outline.copy(alpha=.6f),shape)
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
    Canvas(modifier) {
        if(!decorated) {drawRect(background);return@Canvas}
        val w=size.width;val h=size.height
        drawRect(Brush.verticalGradient(if(dark)listOf(Color(0xFF03101C),Color(0xFF071A35),Color(0xFF38234B),Night)
            else listOf(Color(0xFFE6F5FF),Color(0xFFD9DFFB),Color(0xFFF4DDEC),Color(0xFFF1F6FB))))
        for(i in 0..65) {
            val x=((i*67+13)%313)/313f*w
            val y=((i*43+19)%271)/271f*h*.75f
            drawCircle(if(dark)Color(0xFFB0D7FF).copy(alpha=.12f+(i%4)*.1f) else Color.White,if(i%4==0)1.7f else .8f,Offset(x,y))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFF965CDE).copy(alpha=.22f),Color.Transparent),Offset(w*.9f,h*.32f),w*.75f),w*.75f,Offset(w*.9f,h*.32f))
    }
}

@Composable
fun Cover(url:String,modifier:Modifier=Modifier,description:String?=null) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF143B59),Color(0xFF213052)))),contentAlignment=Alignment.Center) {
        Icon(Icons.Outlined.MusicNote,description,tint=Blue,modifier=Modifier.size(30.dp))
        val model=artworkModel(url,LocalOffline.current)
        if(model!=null)AsyncImage(model,description,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop,
            error=androidx.compose.ui.graphics.painter.ColorPainter(Color.Transparent))
    }
}
@Composable
fun PageTitle(title:String,subtitle:String) {
    Column(Modifier.padding(bottom=16.dp)) {
        Text(title,style=MaterialTheme.typography.headlineMedium)
        Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
    }
}
@Composable
fun EmptyState(title:String,text:String,action:String?=null,onAction:()->Unit={}) {
    Column(Modifier.fillMaxWidth().glass().padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.MusicNote,null,tint=Blue,modifier=Modifier.size(32.dp))
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(action!=null)Button(onAction){Text(action)}
    }
}
fun clock(ms:Long):String = "%d:%02d".format(java.util.Locale.ROOT,ms.coerceAtLeast(0)/60_000,ms.coerceAtLeast(0)/1000%60)

/** Every Material role is intentional: dialogs, chips and sliders share the app palette. */
internal fun neDotifyColorScheme(dark:Boolean,primary:Color,onPrimary:Color):ColorScheme {
    val surface=if(dark)Color(0xFF0B1C2C) else Color.White
    val background=if(dark)Night else Color(0xFFF1F6FB)
    val ink=if(dark)Color(0xFFF2F7FF) else Color(0xFF112334)
    val muted=if(dark)Color(0xFFA9C4D9) else Color(0xFF486174)
    val variant=if(dark)Color(0xFF243747) else Color(0xFFDDE8F0)
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
        outline=if(dark)Color(0xFF68859A) else Color(0xFF6D8597),outlineVariant=if(dark)Color(0xFF314B60) else Color(0xFFB8CEDD),
        scrim=Color.Black,error=if(dark)Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        onError=if(dark)Color(0xFF690005) else Color.White,errorContainer=if(dark)Color(0xFF93000A) else Color(0xFFFFDAD6),
        onErrorContainer=if(dark)Color(0xFFFFDAD6) else Color(0xFF410002),
        surfaceBright=if(dark)Color(0xFF314457) else Color.White,surfaceDim=if(dark)Color(0xFF07121D) else Color(0xFFD5E0EA),
        surfaceContainerLowest=if(dark)Color(0xFF06111B) else Color.White,
        surfaceContainerLow=if(dark)Color(0xFF0E2131) else Color(0xFFF3F8FC),
        surfaceContainer=if(dark)Color(0xFF142838) else Color(0xFFEBF2F8),
        surfaceContainerHigh=if(dark)Color(0xFF1C3042) else Color(0xFFE3EDF5),
        surfaceContainerHighest=if(dark)Color(0xFF273D50) else Color(0xFFDCE8F1))
}
