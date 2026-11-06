package ru.nedotify.app.ui

import org.json.JSONArray
import org.json.JSONObject

enum class RootTab { HOME, SEARCH, LIBRARY }
enum class AppPage { HOME, SEARCH, LIBRARY, PROFILE, SETTINGS, DOWNLOADS, COLLECTION }

/** Navigation carries durable identifiers, never tracks, credentials or stream URLs. */
data class AppDestination(val page:AppPage,val itemId:String="",val filter:String="")

fun rootStacks():Map<RootTab,List<AppDestination>> = RootTab.entries.associateWith {
    listOf(AppDestination(AppPage.valueOf(it.name)))
}

data class AppNavigationState(
    val selectedTab:RootTab=RootTab.HOME,
    val stacks:Map<RootTab,List<AppDestination>> = rootStacks(),
    val playerOpen:Boolean=false,
) {
    val current:AppDestination get()=stacks[selectedTab]?.lastOrNull()?:AppDestination(AppPage.valueOf(selectedTab.name))
    val canGoBack:Boolean get()=playerOpen || stacks[selectedTab].orEmpty().size>1 || selectedTab!=RootTab.HOME

    fun selectTab(tab:RootTab):AppNavigationState=copy(selectedTab=tab)

    fun open(destination:AppDestination):AppNavigationState {
        if(!destination.isValid())return this
        val root=RootTab.entries.firstOrNull {it.name==destination.page.name}
        if(root!=null)return copy(selectedTab=root,stacks=stacks+(root to listOf(destination)))
        val previous=stacks[selectedTab].orEmpty().ifEmpty {rootStacks().getValue(selectedTab)}
        if(previous.last()==destination)return this
        val next=previous+destination
        return copy(stacks=stacks+(selectedTab to if(next.size<=8)next else listOf(previous.first())+next.takeLast(7)))
    }

    fun showPlayer(open:Boolean=true):AppNavigationState=copy(playerOpen=open)

    fun back():AppNavigationState=when {
        playerOpen->copy(playerOpen=false)
        stacks[selectedTab].orEmpty().size>1->copy(stacks=stacks+(selectedTab to stacks.getValue(selectedTab).dropLast(1)))
        selectedTab!=RootTab.HOME->copy(selectedTab=RootTab.HOME)
        else->this
    }
}

private fun String.isCompactArgument(limit:Int):Boolean = length<=limit && none {it.isISOControl()} &&
    !contains("://") && !startsWith("data:",ignoreCase=true) && !startsWith("file:",ignoreCase=true)

private fun AppDestination.isValid():Boolean = itemId.isCompactArgument(128) && filter.isCompactArgument(32) &&
    if(page==AppPage.COLLECTION)itemId.isNotBlank() else itemId.isEmpty()

/** Bounded JSON also permits restoration on API 26 without serializing domain objects. */
fun encodeNavigation(state:AppNavigationState):String {
    val stacks=JSONObject()
    for(tab in RootTab.entries) {
        val rows=JSONArray()
        state.stacks[tab].orEmpty().forEach {destination->
            rows.put(JSONObject().put("page",destination.page.name).put("id",destination.itemId).put("filter",destination.filter))
        }
        stacks.put(tab.name,rows)
    }
    val raw=JSONObject().put("version",1).put("tab",state.selectedTab.name).put("player",state.playerOpen).put("stacks",stacks).toString()
    return if(raw.length<=8192 && decodeNavigation(raw)==state)raw else encodeNavigation(AppNavigationState())
}

fun decodeNavigation(raw:String):AppNavigationState=runCatching {
    require(raw.length<=8192)
    val json=JSONObject(raw)
    require(json.getInt("version")==1)
    val selected=RootTab.valueOf(json.getString("tab"))
    val stacksJson=json.getJSONObject("stacks")
    val stacks=RootTab.entries.associateWith {tab->
        val rows=stacksJson.getJSONArray(tab.name)
        require(rows.length() in 1..8)
        List(rows.length()) {index->
            val row=rows.getJSONObject(index)
            AppDestination(AppPage.valueOf(row.getString("page")),row.getString("id"),row.getString("filter")).also {
                require(it.isValid())
                if(index==0)require(it.page.name==tab.name)
                else require(it.page.name !in RootTab.entries.map(RootTab::name))
            }
        }
    }
    AppNavigationState(selected,stacks,json.getBoolean("player"))
}.getOrDefault(AppNavigationState())
