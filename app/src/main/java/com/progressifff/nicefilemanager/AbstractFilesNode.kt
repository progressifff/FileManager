package com.progressifff.nicefilemanager

import com.progressifff.nicefilemanager.models.FilesNode
import com.progressifff.nicefilemanager.models.StorageFile
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.observers.DisposableSingleObserver
import io.reactivex.schedulers.Schedulers

abstract class AbstractFilesNode(val folder: AbstractStorageFile) {
    var files = arrayListOf<AbstractStorageFile>()
    protected set
    val eventsListeners = ArrayList<EventsListener>()
    val isEmpty: Boolean get() = files.isEmpty()
    var isLoaded = false
        private set

    private lateinit var loadFilesDisposable: Disposable
    private lateinit var sortFilesDisposable: Disposable

    var sortFilesType = SortFilesType.NAME
    set(value) {
        if(field != value){
            field = value
            sort()
        }
    }

    var isDescendingSort = true
        set(value) {
            if(field != value){
                field = value
                sort()
            }
        }

    fun subscribe(listener: EventsListener){
        eventsListeners.add(listener)
    }

    fun unsubscribe(listener: EventsListener){
        eventsListeners.remove(listener)
    }

    fun sort(){
        if(files.isEmpty()) {
            return
        }
        if(::sortFilesDisposable.isInitialized && !sortFilesDisposable.isDisposed){
            sortFilesDisposable.dispose()
        }
        for(observer in eventsListeners){
            observer.onStartUpdate()
        }
        sortFilesDisposable = getSortObject(files)
            .subscribeWith(object : DisposableSingleObserver<ArrayList<AbstractStorageFile>>() {
                override fun onSuccess(files: ArrayList<AbstractStorageFile>) {
                    this@AbstractFilesNode.files = files
                    for(observer in eventsListeners){
                        observer.onUpdated()
                    }
                }

                override fun onError(e: Throwable) {
                    e.printStackTrace()
                    for(observer in eventsListeners){
                        observer.onError(R.string.sort_files_error)
                    }
                }
            }
        )
    }

    fun load() {
        if(::loadFilesDisposable.isInitialized && !loadFilesDisposable.isDisposed){
            loadFilesDisposable.dispose()
        }
        for(observer in eventsListeners){
            observer.onStartUpdate()
        }
        val filesSource = folder.openAsDir()
        loadFilesDisposable = filesSource.flatMap { data -> //Reorder: at first folders, then files
            Single.create<ArrayList<AbstractStorageFile>>{
                val files = arrayListOf<AbstractStorageFile>()
                val folders = arrayListOf<AbstractStorageFile>()
                val result = arrayListOf<AbstractStorageFile>()
                for(file in data){
                    if(it.isDisposed) {
                        return@create
                    }
                    if(!check(file)) {
                        continue
                    }
                    if(!file.isDirectory){
                        files.add(file)
                    }
                    else{
                        folders.add(file)
                    }
                }
                result.addAll(folders)
                result.addAll(files)
                if(!it.isDisposed) {
                    it.onSuccess(result)
                }
            }
        }
        .flatMap { files -> getSortObject(files) }
        .subscribeWith(object : DisposableSingleObserver<ArrayList<AbstractStorageFile>>(){
            override fun onSuccess(files: ArrayList<AbstractStorageFile>) {
                this@AbstractFilesNode.files = files
                isLoaded = true
                for(observer in eventsListeners){
                    observer.onUpdated()
                }
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
                for(observer in eventsListeners){
                    observer.onError(R.string.load_files_error)
                }
            }
        })
    }

    fun get(index: Int): AbstractStorageFile = files.elementAt(index)

    open fun release() {}

    private fun getSortObject(files: ArrayList<AbstractStorageFile>): Single<ArrayList<AbstractStorageFile>> {
        val sortList: (list: MutableList<AbstractStorageFile>) -> Unit = { _files ->

            val sorted = when(sortFilesType) {
                SortFilesType.NAME ->                 { if(isDescendingSort) _files.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it -> it.name})
                                                        else _files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {it -> it.name})}
                SortFilesType.SIZE ->                 { if(isDescendingSort) _files.sortedByDescending {it.size}
                                                        else _files.sortedBy {it.size}}
                SortFilesType.TYPE ->                 { if(isDescendingSort) _files.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it -> it.extension})
                                                        else _files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {it -> it.extension})}
                SortFilesType.MODIFICATION_TIME ->    { if(isDescendingSort) _files.sortedByDescending {it.lastModified}
                                                        else _files.sortedBy {it.lastModified}}
            }
            _files.clear()
            _files.addAll(sorted)
        }

        return Single.create<ArrayList<AbstractStorageFile>> { emitter ->
            val storageFiles = ArrayList(files)
            val lastDirectoryIndex = storageFiles.indexOfLast { it.isDirectory  }
            //Sort folders
            if(!emitter.isDisposed && (lastDirectoryIndex != -1)){
                val folders = storageFiles.subList(0, lastDirectoryIndex + 1)
                sortList(folders)
            }
            //Sort files
            if(!emitter.isDisposed && (lastDirectoryIndex + 1 < storageFiles.size)) {
                sortList(storageFiles.subList(lastDirectoryIndex + 1, files.size))
            }
            if(!emitter.isDisposed) {
                emitter.onSuccess(storageFiles)
            }
        }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
    }

    protected open fun check(file: AbstractStorageFile): Boolean = true

    protected fun insert(file: AbstractStorageFile): Int{

        val compare: (first: AbstractStorageFile, second: AbstractStorageFile) -> Boolean = { first, second ->
            when(sortFilesType){
                SortFilesType.NAME -> {
                    val compareRes = first.name.compareTo(second.name, true)
                    if(isDescendingSort) compareRes < 0 else compareRes > 0
                }
                SortFilesType.SIZE -> { if(isDescendingSort) first.size > second.size else second.size < first.size }
                SortFilesType.TYPE -> {
                    val compareRes = first.name.compareTo(second.name, true)
                    if(isDescendingSort) compareRes < 0 else compareRes > 0
                }
                SortFilesType.MODIFICATION_TIME -> { if(isDescendingSort) first.lastModified > second.lastModified else second.lastModified < first.lastModified }
            }
        }

        val insertTo: (startIndex: Int, subList: MutableList<AbstractStorageFile>)->Int = {startIndex, subList->
            val filesIterator = subList.listIterator(subList.size)
            var index = subList.size
            while(filesIterator.hasPrevious() && compare(filesIterator.previous(), file)) {
                index --
            }
            when (index) {
                subList.size -> {
                    subList.add(file)
                    startIndex + subList.size - 1
                }
                -1 -> {
                    subList.add(0, file)
                    startIndex
                }
                else -> {
                    subList.add(index, file)
                    startIndex + index
                }
            }
        }

        val lastDirectoryIndex = files.indexOfLast { it.isDirectory  }
        var startIndex = 0
        return if(file.isDirectory){
            val folders = files.subList(startIndex, lastDirectoryIndex + 1)
            insertTo(startIndex, folders)
        }
        else{
            startIndex = lastDirectoryIndex + 1
            val files = files.subList(startIndex, files.size)
            insertTo(startIndex, files)
        }
    }

    companion object {
        fun create(source: AbstractStorageFile): AbstractFilesNode{
            if(source is StorageFile) {
                return FilesNode(source)
            }
            throw AssertionError("Unsupported type")
        }
    }

    interface EventsListener{
        fun onFileRemoved(index: Int)
        fun onFileCreated(index: Int)
        fun onUpdated()
        fun onStartUpdate()
        fun onError(messageId: Int)
    }

    enum class SortFilesType{
        NAME,
        SIZE,
        TYPE,
        MODIFICATION_TIME;

        companion object {
            fun fromString(name: String): SortFilesType{
                return try{
                    valueOf(name)
                } catch (e: Exception){
                    e.printStackTrace()
                    NAME
                }
            }
        }
    }
}