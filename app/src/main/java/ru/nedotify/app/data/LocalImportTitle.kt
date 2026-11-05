package ru.nedotify.app.data

internal fun localImportTitle(displayName:String?,lastSegment:String?):String {
    val name=listOfNotNull(displayName,lastSegment).firstOrNull {it.isNotBlank()}.orEmpty()
    return localMetadataText(name.substringBeforeLast('.')).ifBlank {"Аудиофайл"}
}

internal fun localMetadataText(raw:String?,limit:Int=2_048):String {
    if(raw==null)return ""
    val output=StringBuilder();var index=0
    while(index<raw.length) {
        val point=Character.codePointAt(raw,index);val count=Character.charCount(point);index+=count
        if(point in 0xD800..0xDFFF || Character.isISOControl(point))continue
        if(output.length+count>limit)break
        output.appendCodePoint(point)
    }
    return output.toString().trim()
}
