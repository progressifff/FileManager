package com.progressifff.nicefilemanager

import android.support.annotation.StringRes
import io.reactivex.Completable
import io.reactivex.disposables.Disposable
import io.reactivex.observers.DisposableCompletableObserver
import java.lang.Exception
import java.util.concurrent.atomic.AtomicReference

object FilesTasksManager {
    private val tasks = arrayListOf<AbstractTask>()
    private val tasksIndicesMap = hashMapOf<Int, Int>()
    val tasksCount: Int get() = tasks.size
    var eventsListener: EventsListener? = null

    private val taskListener = object: AbstractTask.TaskListener {

        @Synchronized
        override fun onTaskUpdated(task: AbstractTask) {
            val taskId = task.id
            App.get().handler.post {
                if (tasksIndicesMap.containsKey(taskId)) {
                    eventsListener?.onTaskUpdated(tasksIndicesMap[task.id]!!)
                }
            }
        }

        @Synchronized
        override fun onError(messageId: Int) {
            App.get().handler.post {
                eventsListener?.onError(messageId)
            }
        }

        override fun onTaskCompleted(task: AbstractTask) {
            val taskIndex = tasksIndicesMap[task.id]!!
            removeTask(taskIndex)
            eventsListener?.onTaskUpdated(taskIndex, true)
        }
    }

    fun removeTask(index: Int){
        try{
            val taskToRemove = tasks.removeAt(index)
            taskToRemove.dispose()
            tasksIndicesMap.remove(taskToRemove.id)
            for((i, task) in tasks.withIndex()) {
                tasksIndicesMap[task.id] = i
            }
            eventsListener?.onTaskUpdated(index, true)
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun add(task: AbstractTask){
        if(task.files.isEmpty()) {
            return
        }
        task.listener = taskListener
        val executeTask: () -> Unit = {
            tasks.add(task)
            tasksIndicesMap[task.id] = tasks.size - 1
            eventsListener?.onNewTask()
            task.execute()
        }
        if(task is CopyCutTask){
            if(task.destFolder.path == task.files.first().parent!!.path){
                if(task is CutTask) {
                    eventsListener?.onError(R.string.cut_to_same_folder)
                    return
                }
                else {
                    task.existingFileAction = AbstractStorageFile.ExistingFileAction.SAVE_BOTH
                }
            }
            else{
                if(task.existingFilesNames.isNotEmpty()){
                    val callback: (action: AbstractStorageFile.ExistingFileAction) -> Unit = { action ->
                        task.existingFileAction = action
                        executeTask()
                    }
                    eventsListener?.onProcessingExistingFiles(callback, task.existingFilesNames)
                    return
                }
            }
        }
        executeTask()
    }

    fun get(index: Int): AbstractTask{
        return tasks.elementAt(index)
    }

    interface EventsListener{
        fun onNewTask()
        fun onTaskUpdated(taskIndex: Int, completed: Boolean = false)
        fun onProcessingExistingFiles(callback: (existingFileAction: AbstractStorageFile.ExistingFileAction) -> Unit, existingFilesNames: ArrayList<String>) {}
        fun onError(@StringRes messageId: Int)
    }
}

abstract class AbstractTask(val files: List<AbstractStorageFile>){
    val id: Int get() = hashCode()
    var currentProcessingFile = AtomicReference<String>("")
    var progress = AtomicReference(0)
    val disposed: Boolean get() = ::disposable.isInitialized && disposable.isDisposed
    var listener: AbstractTask.TaskListener? = null
    abstract val processingFilesCount: Int
    private var filesSize = 0L

    protected val calculateFilesSizeTask = Completable.create{ emitter ->
        for(file in files){
            filesSize += file.size
        }
        emitter.onComplete()
    }!!

    private lateinit var disposable: Disposable

    protected fun updateProgress(bytesCount: Long){
        progress.set((bytesCount.toFloat() / filesSize * 100).toInt())
        listener?.onTaskUpdated(this)
    }

    protected fun updateCurrentProcessingFile(file: AbstractStorageFile){
        currentProcessingFile.set(file.name)
        listener?.onTaskUpdated(this)
    }

    protected fun subscribe(completable: Completable){
        disposable = completable.subscribeWith(object: DisposableCompletableObserver(){
            override fun onComplete() {
                listener?.onTaskCompleted(this@AbstractTask)
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
                listener?.onTaskCompleted(this@AbstractTask)
            }
        })
    }

    abstract fun execute()

    fun dispose(){
        if(::disposable.isInitialized && !disposable.isDisposed){
            disposable.dispose()
            listener?.onTaskUpdated(this)
        }
    }

    interface TaskListener{
        fun onTaskUpdated(task: AbstractTask)
        fun onTaskCompleted(task: AbstractTask)
        fun onError(@StringRes messageId: Int)
    }
}

abstract class CopyCutTask(files: List<AbstractStorageFile>,
                           val destFolder: AbstractStorageFile,
                           var existingFileAction: AbstractStorageFile.ExistingFileAction = AbstractStorageFile.ExistingFileAction.REWRITE) : AbstractTask(files){

    val existingFilesNames = arrayListOf<String>()

    override val processingFilesCount: Int get() {
        return if(existingFileAction == AbstractStorageFile.ExistingFileAction.SKIP){
            files.count() - existingFilesNames.size
        }
        else{
            files.count()
        }
    }

    init{
        for(file in files) {
            if(destFolder.contains(file.name)){
                existingFilesNames.add(file.name)
            }
        }
    }
}

class CopyTask(files: List<AbstractStorageFile>,
               destFolder: AbstractStorageFile,
               existingFileAction: AbstractStorageFile.ExistingFileAction = AbstractStorageFile.ExistingFileAction.REWRITE) : CopyCutTask(files, destFolder, existingFileAction){

    override fun execute() {
        if(files.isNotEmpty()){
            var task = calculateFilesSizeTask
            val it = files.iterator()
            var bytesCopied = 0L
            do{
                var prev = 0L
                val currentTask = it.next().copyRecursivelyAsync(destFolder.path,
                        { bytes ->
                            bytesCopied += (bytes - prev)
                            updateProgress(bytesCopied)
                            prev = bytes
                        },
                        ::updateCurrentProcessingFile,
                        { _, messageId ->
                            listener?.onError(messageId)
                            OnErrorAction.SKIP
                        },
                        existingFileAction
                )
                task = task.concatWith(currentTask)
            } while (it.hasNext())
            subscribe(task)
        }
    }
}

class CutTask(files: List<AbstractStorageFile>,
              destFolder: AbstractStorageFile,
              existingFileAction: AbstractStorageFile.ExistingFileAction = AbstractStorageFile.ExistingFileAction.REWRITE) : CopyCutTask(files, destFolder, existingFileAction) {

    override fun execute() {
        if(files.isEmpty()){
            return
        }
        val task = calculateFilesSizeTask.concatWith(Completable.create { emitter ->
            var bytesProcessed = 0L
            val it = files.iterator()
            do{
                val currentFile = it.next()
                updateCurrentProcessingFile(currentFile)
                try{
                    currentFile.move(destFolder.path, existingFileAction)
                }
                catch (e: Exception){
                    e.printStackTrace()
                }
                bytesProcessed += currentFile.size
                updateProgress(bytesProcessed)
            } while (it.hasNext())
            emitter.onComplete()
        })
        subscribe(task)
    }
}

class DeleteTask(files: List<AbstractStorageFile>) : AbstractTask(files){
    override val processingFilesCount get() = files.count()
    override fun execute() {
        if(files.isEmpty()){
            return
        }
        var task = calculateFilesSizeTask
        val it = files.iterator()
        var bytesProcessed = 0L
        do{
            var prev = 0L
            val currentTask = it.next().deleteRecursivelyAsync(
                                                                {   bytes ->
                                                                    bytesProcessed += (bytes - prev)
                                                                    updateProgress(bytesProcessed)
                                                                    prev = bytes
                                                                },
                                                                { file ->
                                                                    RxBus.publish(RxEvent.FileDeletedEvent(file))
                                                                    updateCurrentProcessingFile(file)
                                                                }
                                                              )
            task = task.concatWith(currentTask)
        } while (it.hasNext())
        subscribe(task)
    }
}