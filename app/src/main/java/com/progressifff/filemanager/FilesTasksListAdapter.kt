package com.progressifff.filemanager

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.progressifff.filemanager.presenters.FilesTasksPresenter
import com.progressifff.filemanager.views.FileTaskView

class FilesTasksListAdapter(private val presenter: FilesTasksPresenter) : RecyclerView.Adapter<FilesTasksListAdapter.FilesTasksListViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilesTasksListViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.files_tasks_list_item, parent, false)
        return FilesTasksListViewHolder(v)
    }

    override fun getItemCount(): Int {
        return presenter.tasksCount
    }

    override fun onBindViewHolder(holder: FilesTasksListViewHolder, position: Int) {
        presenter.onBindFileTaskView(holder, position)
    }

    inner class FilesTasksListViewHolder(v: View) : RecyclerView.ViewHolder(v), FileTaskView {
        private val fileTaskTitle: TextView = v.findViewById(R.id.fileTaskTitle)
        private val processedFileName: TextView = v.findViewById(R.id.processedFileName)
        private val fileTaskProgress: TextView = v.findViewById(R.id.fileTaskProgress)
        private val fileTaskProgressBar: ProgressBar = v.findViewById(R.id.fileTaskProgressBar)
        private val cancelTaskBtn: ImageButton = v.findViewById(R.id.cancelTaskBtn)

        init {
            cancelTaskBtn.setOnClickListener {
                presenter.onCancelTask(adapterPosition)
            }
        }

        override fun setTaskTitle(title: String){
            if(fileTaskTitle.text != title) {
                fileTaskTitle.text = title
            }
        }

        override fun setProcessedFileName(fileName: String){
            if(processedFileName.text != fileName) {
                processedFileName.text = fileName
            }
        }

        override fun setFileTaskProgress(taskProgress: Int){
            val progress = "$taskProgress% ${getStringFromRes(R.string.task_progress_complete)}"
            fileTaskProgress.text = progress

            if(fileTaskProgressBar.isIndeterminate){
                fileTaskProgressBar.isIndeterminate = false
            }

            fileTaskProgressBar.progress = taskProgress
        }
    }
}