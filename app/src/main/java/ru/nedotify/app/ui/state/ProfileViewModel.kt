package ru.nedotify.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    val profile=repository.preferences.profile.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),UserProfile())
    private val _profileSave=MutableStateFlow(ProfileSaveState())
    val profileSave=_profileSave.asStateFlow()
    private val _reportPeriod=MutableStateFlow(runCatching {ReportPeriod.valueOf(savedState.get<String>("report_period")?:"WEEK")}.getOrDefault(ReportPeriod.WEEK))
    val reportPeriod=_reportPeriod.asStateFlow()
    private val reportRetry=MutableStateFlow(0)
    private val reportClock=flow {
        while(currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis() to java.time.ZoneId.systemDefault());delay(60_000)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val report=reportPeriod.flatMapLatest {period->
        combine(reportRetry,reportClock) {_,time->time}
            .flatMapLatest {time->ListeningReports(repository.dao).observe(period,time.first,time.second)
                .map {ReportState(busy=false,report=it)}
                .catch {error->emit(ReportState(busy=false,error=error.message?:"Не удалось загрузить отчёт"))}}
            .onStart {emit(ReportState())}
    }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),ReportState())
    fun updateProfile(name:String,bio:String) {
        if(_profileSave.value.busy)return
        val revision=_profileSave.value.revision
        val draft=ProfileDraft.of(name,bio)
        if(!draft.valid) {_profileSave.value=ProfileSaveState(error=draft.error,revision=revision);return}
        _profileSave.value=ProfileSaveState(busy=true,revision=revision)
        viewModelScope.launch {
            try {repository.preferences.profile(draft.name,draft.bio);_profileSave.value=ProfileSaveState(revision=revision+1)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){_profileSave.value=ProfileSaveState(error=error.message?:"Не удалось сохранить профиль",revision=revision)}
        }
    }
    fun selectAvatar() {events.requestAvatar.tryEmit(Unit)}
    fun removeAvatar()=task {
        val previous=repository.preferences.profile.first().avatarUri
        repository.preferences.removeAvatar();releaseDocumentIfUnused(previous)
    }
    fun attachAvatar(uri:Uri)=task {
        val previous=repository.preferences.profile.first().avatarUri
        try {withContext(Dispatchers.IO) {
            if(uri.scheme!="content")throw SourceFailure("Выберите изображение через системное окно")
            val resolver=repository.context.contentResolver
            if(resolver.getType(uri)?.startsWith("image/")!=true)throw SourceFailure("Выберите изображение")
            resolver.openAssetFileDescriptor(uri,"r")?.use {}?:throw SourceFailure("Не удалось открыть изображение")
            repository.preferences.avatar(uri.toString())
        }} finally {withContext(NonCancellable) {releaseDocumentIfUnused(previous);releaseDocumentIfUnused(uri.toString())}}
    }
    fun selectReportPeriod(period:ReportPeriod) {savedState["report_period"]=period.name;_reportPeriod.value=period}
    fun retryReport() {reportRetry.value++}
}
