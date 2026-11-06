package ru.nedotify.app.testing

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Never boots NeDotifyApp or its download recovery against the installed profile.
 * All targetContext storage APIs resolve into a fresh instrumentation-APK scratch
 * directory. No production directory is cleared, copied, opened or restored.
 */
class IsolatedAndroidTestRunner : AndroidJUnitRunner() {
    private var isolated: FixtureStorageContext?=null
    lateinit var fixtureRoot:File
        private set
    lateinit var productionDataDir:File
        private set

    override fun newApplication(cl:ClassLoader,className:String,context:Context):Application {
        productionDataDir=context.dataDir.canonicalFile
        fixtureRoot=File(super.getContext().cacheDir,"nedotify-instrumentation-${UUID.randomUUID()}").canonicalFile
        check(fixtureRoot.mkdirs()) {"Cannot create isolated instrumentation storage"}
        check(!fixtureRoot.path.startsWith(productionDataDir.path+File.separator)) {"Scratch storage must belong to the instrumentation APK"}
        val fixture=FixtureStorageContext(context,fixtureRoot)
        isolated=fixture
        return super.newApplication(cl,Application::class.java.name,fixture).also {fixture.application=it}
    }

    override fun getTargetContext():Context=isolated?:super.getTargetContext()
}

/** Test-only Context. Unknown absolute paths and directory traversal fail closed. */
internal class FixtureStorageContext(base:Context,private val root:File):ContextWrapper(base) {
    var application:Application?=null
    private val preferences=mutableMapOf<String,SharedPreferences>()
    private fun directory(name:String)=File(root,name).also {check(it.isDirectory || it.mkdirs())}
    private fun entry(directoryName:String,name:String):File {
        require(name.isNotBlank() && name!="." && name!=".." && !File(name).isAbsolute && name.none {it=='/' || it=='\\'}) {"Fixture file name must be local"}
        return File(directory(directoryName),name).canonicalFile.also {
            check(it.path.startsWith(root.canonicalPath+File.separator)) {"Fixture path escaped its root"}
        }
    }
    override fun getApplicationContext():Context=application?:this
    override fun getApplicationInfo():ApplicationInfo=ApplicationInfo(super.getApplicationInfo()).apply {
        dataDir=root.path
        deviceProtectedDataDir=root.path
    }
    override fun getDataDir()=root
    override fun getFilesDir()=directory("files")
    override fun getNoBackupFilesDir()=directory("no-backup")
    override fun getCacheDir()=directory("cache")
    override fun getCodeCacheDir()=directory("code-cache")
    override fun getDir(name:String,mode:Int)=entry("app-dirs",name).also {check(it.isDirectory || it.mkdirs())}
    override fun getFileStreamPath(name:String)=entry("files",name)
    override fun openFileInput(name:String)=FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name:String,mode:Int)=FileOutputStream(getFileStreamPath(name),mode and Context.MODE_APPEND!=0)
    override fun deleteFile(name:String)=getFileStreamPath(name).delete()
    override fun fileList():Array<String> = filesDir.list()?:emptyArray()
    override fun getDatabasePath(name:String)=entry("databases",name)
    override fun deleteDatabase(name:String)=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList():Array<String> = directory("databases").list()?:emptyArray()
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase =
        openOrCreateDatabase(name,mode,factory,null)
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?):SQLiteDatabase {
        var flags=SQLiteDatabase.CREATE_IF_NECESSARY
        if(mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING!=0)flags=flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        if(mode and Context.MODE_NO_LOCALIZED_COLLATORS!=0)flags=flags or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        return SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,flags,errorHandler)
    }
    override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=synchronized(preferences) {
        // Exercise the normal API while keeping preferences strictly in memory.
        entry("shared-preferences",name)
        preferences.getOrPut(name) {MemoryPreferences()}
    }
    override fun deleteSharedPreferences(name:String):Boolean=synchronized(preferences) {preferences.remove(name)!=null}
    override fun moveDatabaseFrom(sourceContext:Context,name:String):Boolean=throw UnsupportedOperationException("Fixture runner cannot migrate external databases")
    override fun moveSharedPreferencesFrom(sourceContext:Context,name:String):Boolean=throw UnsupportedOperationException("Fixture runner cannot migrate external preferences")
    override fun createDeviceProtectedStorageContext():Context=this
    override fun getExternalFilesDir(type:String?):File=if(type==null)directory("external-files") else entry("external-files",type).also {check(it.isDirectory || it.mkdirs())}
    override fun getExternalFilesDirs(type:String?):Array<File> = arrayOf(getExternalFilesDir(type))
    override fun getExternalCacheDir()=directory("external-cache")
    override fun getExternalCacheDirs():Array<File> = arrayOf(getExternalCacheDir())
    override fun getExternalMediaDirs():Array<File> = arrayOf(directory("external-media"))
    override fun getObbDir()=directory("obb")
    override fun getObbDirs():Array<File> = arrayOf(getObbDir())
}

private class MemoryPreferences:SharedPreferences {
    private val values=mutableMapOf<String,Any>()
    private val listeners=mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private fun value(key:String?):Any?=synchronized(values) {values[key]}
    override fun contains(key:String?)=synchronized(values) {values.containsKey(key)}
    override fun getAll():MutableMap<String,*> = synchronized(values) {values.mapValues {(_,v)->if(v is Set<*>)v.toSet() else v}.toMutableMap()}
    override fun getString(key:String?,defValue:String?):String?=value(key)?.let {it as String}?:defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key:String?,defValues:MutableSet<String>?):MutableSet<String>?=(value(key) as Set<String>?)?.toMutableSet()?:defValues?.toMutableSet()
    override fun getInt(key:String?,defValue:Int)=value(key)?.let {it as Int}?:defValue
    override fun getLong(key:String?,defValue:Long)=value(key)?.let {it as Long}?:defValue
    override fun getFloat(key:String?,defValue:Float)=value(key)?.let {it as Float}?:defValue
    override fun getBoolean(key:String?,defValue:Boolean)=value(key)?.let {it as Boolean}?:defValue
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {if(listener!=null)synchronized(values) {listeners+=listener}}
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {synchronized(values) {listeners.remove(listener)}}
    override fun edit():SharedPreferences.Editor=object:SharedPreferences.Editor {
        private val changes=mutableMapOf<String,Any?>()
        private var clear=false
        private fun put(key:String?,value:Any?):SharedPreferences.Editor {requireNotNull(key);changes[key]=value;return this}
        override fun putString(key:String?,value:String?)=put(key,value)
        override fun putStringSet(key:String?,values:MutableSet<String>?)=put(key,values?.toSet())
        override fun putInt(key:String?,value:Int)=put(key,value)
        override fun putLong(key:String?,value:Long)=put(key,value)
        override fun putFloat(key:String?,value:Float)=put(key,value)
        override fun putBoolean(key:String?,value:Boolean)=put(key,value)
        override fun remove(key:String?)=put(key,null)
        override fun clear():SharedPreferences.Editor {clear=true;return this}
        override fun apply() {commit()}
        override fun commit():Boolean {
            val changed=mutableSetOf<String>()
            val callbacks=synchronized(values) {
                if(clear) {changed+=values.keys;values.clear()}
                for((key,value) in changes) {
                    if(values[key]!=value)changed+=key
                    if(value==null)values.remove(key) else values[key]=value
                }
                listeners.toList()
            }
            changed.forEach {key->callbacks.forEach {it.onSharedPreferenceChanged(this@MemoryPreferences,key)}}
            return true
        }
    }
}
