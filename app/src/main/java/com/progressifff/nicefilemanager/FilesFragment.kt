package com.progressifff.nicefilemanager

import android.os.Bundle
import android.os.Parcelable
import android.support.annotation.StringRes
import android.support.design.widget.AppBarLayout
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import com.progressifff.nicefilemanager.presenters.FilesPresenter
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.GridLayoutManager
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import com.progressifff.nicefilemanager.dialogs.*
import com.progressifff.nicefilemanager.presenters.MainPresenter
import android.util.TypedValue
import java.lang.Exception
import android.widget.LinearLayout
import android.widget.Toast
import com.progressifff.nicefilemanager.views.NestedFilesView

class FilesFragment : Fragment(), NestedFilesView {
    private lateinit var noFilesMessage: LinearLayout
    private lateinit var filesList: RecyclerView
    private lateinit var filesListAdapter: RecyclerView.Adapter<*>
    private lateinit var filesListLinearLayoutManager: LinearLayoutManager
    private lateinit var filesListGridLayoutManager: GridLayoutManager
    private lateinit var filesListGridLayoutDecoration: RecyclerView.ItemDecoration
    private lateinit var updateViewProgressBar: ProgressBar
    private lateinit var filesListRefresher: SwipeRefreshLayout
    private lateinit var presenter: FilesPresenter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        setHasOptionsMenu(true)
        presenter = if(savedInstanceState == null){
            FilesPresenter(AppPreferences, RxBus, FilesClipboard, FileImageManager)
        }
        else try{
            PresenterManager.restorePresenter<FilesPresenter>(savedInstanceState)
        }
        catch (e: Exception){
            e.printStackTrace()
            FilesPresenter(AppPreferences, RxBus, FilesClipboard, FileImageManager)
        }

        activity!!.findViewById<FloatingActionButton>(R.id.addFolderFab).setOnClickListener(presenter.viewClickListener)
        val view = inflater.inflate(R.layout.files_fragment, container, false)

        noFilesMessage = view.findViewById(R.id.noFilesMsgView)
        updateViewProgressBar = view.findViewById(R.id.loadFilesProgressBar)

        filesListRefresher = view.findViewById(R.id.filesListRefresher)
        val typedValue = TypedValue()
        activity!!.theme.resolveAttribute(R.attr.layoutRefresherColor, typedValue, true)
        filesListRefresher.setProgressBackgroundColorSchemeColor(typedValue.data)
        activity!!.theme.resolveAttribute(R.attr.colorAccent, typedValue, true)
        filesListRefresher.setColorSchemeColors(typedValue.data)
        filesListRefresher.setOnRefreshListener(presenter)
        initFilesList(view)
        return view
    }

    private fun initFilesList(root: View) {
        filesList = root.findViewById(R.id.filesList)
        filesListAdapter = FilesListAdapter(presenter)
        filesListLinearLayoutManager = LinearLayoutManager(context)
        filesListGridLayoutManager = GridLayoutManager(context, Utils.calculateGridColumnsCount())
        filesListGridLayoutDecoration = RecyclerViewGridLayoutDecoration()
        filesList.apply{
            setHasFixedSize(true)
            setItemViewCacheSize(40)
            adapter = filesListAdapter
        }
        filesList.addOnScrollListener(object : RecyclerView.OnScrollListener(){
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                filesListRefresher.isEnabled = (recyclerView.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition() == 0
            }
        })
        val filesDisplayMode = MainPresenter.FilesDisplayMode.fromString(AppPreferences.getString(Constants.FILES_DISPLAY_MODE_KEY, MainPresenter.FilesDisplayMode.LIST.name))
        when(filesDisplayMode){
            MainPresenter.FilesDisplayMode.LIST -> filesList.layoutManager = filesListLinearLayoutManager
            MainPresenter.FilesDisplayMode.GRID -> {
                filesList.layoutManager = filesListGridLayoutManager
                filesList.addItemDecoration(filesListGridLayoutDecoration)
            }
        }
        filesList.runOnLayoutChanged { setupToolBarScrollingBehavior(filesList.isScrollable) }
    }

    override fun onStart() {
        super.onStart()
        presenter.bindView(this)
    }

    override fun onStop(){
        super.onStop()
        presenter.unbindView()
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        inflater!!.inflate(R.menu.files_fragment_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?) {
        presenter.onPrepareOptionsMenu(menu!!)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return presenter.onOptionsItemSelected(item!!)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        PresenterManager.savePresenter(presenter, outState)
    }

    override fun showDeleteFilesDialog(filesCount: Int) {
        DeleteFilesDialog.createInstance(filesCount).show(childFragmentManager, DeleteFilesDialog::class.java.name)
    }

    override fun setFilesInListLayout() {
        if(filesList.layoutManager is GridLayoutManager){
            val firstVisibleItemPosition = (filesList.layoutManager as? GridLayoutManager)?.findFirstCompletelyVisibleItemPosition()
            filesList.layoutManager = filesListLinearLayoutManager
            filesList.removeItemDecoration(filesListGridLayoutDecoration)
            filesList.adapter = filesListAdapter
            filesList.runOnLayoutChanged {
                setupToolBarScrollingBehavior(filesList.isScrollable)
                filesList.adapter = filesListAdapter
                if(firstVisibleItemPosition != null) filesList.layoutManager!!.scrollToPosition(firstVisibleItemPosition)
            }
        }
    }

    override fun setFilesInGridLayout() {
        if(filesList.layoutManager !is GridLayoutManager){
            val firstVisibleItemPosition = (filesList.layoutManager as? LinearLayoutManager)?.findFirstCompletelyVisibleItemPosition()
            filesListGridLayoutManager.spanCount = Utils.calculateGridColumnsCount()
            filesList.layoutManager = filesListGridLayoutManager
            filesList.adapter = filesListAdapter
            filesList.runOnLayoutChanged {
                setupToolBarScrollingBehavior(filesList.isScrollable)
                filesList.addItemDecoration(filesListGridLayoutDecoration)
                filesList.adapter = filesListAdapter

                if(firstVisibleItemPosition != null) filesList.layoutManager!!.scrollToPosition(firstVisibleItemPosition)
            }
        }
    }

    override fun setupToolBarScrollingBehavior(isEnabled: Boolean){
        if(activity != null){
            val toolBarLayoutParams = (activity as MainActivity).toolBar.layoutParams as AppBarLayout.LayoutParams
            toolBarLayoutParams.scrollFlags =  if(isEnabled){
                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
            }
            else{ 0 } //disable scrolling behavior
            (activity as MainActivity).appBarLayout.requestLayout()
        }
    }

    override fun restoreFilesListState(filesListSavedState: Parcelable?) {
        if(filesListSavedState != null) {
            filesList.layoutManager!!.onRestoreInstanceState(filesListSavedState)
        }
    }

    override fun showNoFilesMsg() {
        updateViewProgressBar.visible = false
        filesList.visible = false
        noFilesMessage.visible = true
        filesListRefresher.isEnabled = true
        setupToolBarScrollingBehavior(false)
    }

    override fun showProgressBar() {
        noFilesMessage.visible = false
        filesList.visible = false
        updateViewProgressBar.visible = true
    }

    override fun showFilesList() {
        noFilesMessage.visible = false
        updateViewProgressBar.visible = false
        filesList.visible = true
    }

    override fun update(animate: Boolean, resetListScrollPosition: Boolean) {
        if(resetListScrollPosition){
            filesList.scrollToPosition(0)
        }
        if(presenter.getFilesCount() == 0){
            showNoFilesMsg()
        }
        else{
            showFilesList()
            if(animate){
                val animationController = AnimationUtils.loadLayoutAnimation(context, R.anim.files_list_layout_animation)
                filesList.layoutAnimation = animationController
                filesList.scheduleLayoutAnimation()
            }
        }
        filesListAdapter.notifyDataSetChanged()
        filesList.runOnLayoutChanged { setupToolBarScrollingBehavior(!presenter.multiSelectMode.running && filesList.isScrollable) }
    }

    override fun showFileDetailsDialog(file: AbstractStorageFile) {
        FileDetailsDialog.createInstance(file).show(childFragmentManager, FileDetailsDialog::class.java.name)
    }

    override fun invalidateMenu() {
        activity?.invalidateOptionsMenu()
    }

    override fun showCreateFolderDialog(parentFolder: AbstractStorageFile) {
        CreateFolderDialog.createInstance(parentFolder).show(childFragmentManager, RenameStorageFileDialog::class.java.name)
    }

    override fun startActionMode(multiSelectMode: MultiSelectMode) {
        val mainActivity = activity!! as AppCompatActivity
        mainActivity.startSupportActionMode(multiSelectMode)
        setupToolBarScrollingBehavior(false)
    }

    override fun showSortTypeDialog(sortType: AbstractFilesNode.SortFilesType) {
        SortTypeDialog.createInstance(sortType).show(childFragmentManager, SortTypeDialog::class.java.name)
    }

    override fun showRenameFileDialog(file: AbstractStorageFile) {
        RenameStorageFileDialog.createInstance(file).show(childFragmentManager, RenameStorageFileDialog::class.java.name)
    }

    override fun showFileActionsDialog(file: AbstractStorageFile) {
        FileActionsDialog.createInstance(file).show(childFragmentManager, FileActionsDialog::class.java.name)
    }

    override fun invalidateFilesList() {
        filesListAdapter.notifyDataSetChanged()
    }

    override fun updateFilesListEntry(index: Int) = filesListAdapter.notifyItemChanged(index)

    override fun insertFilesListEntry(index: Int) {
        if(noFilesMessage.visible){
            showFilesList()
        }
        filesList.itemAnimator!!.endAnimations()
        filesListAdapter.notifyItemInserted(index)
        filesList.runOnLayoutChanged { setupToolBarScrollingBehavior(filesList.isScrollable) }
    }

    override fun removeFilesListEntry(index: Int) {
        filesList.itemAnimator!!.endAnimations()
        filesListAdapter.notifyItemRemoved(index)
        if(presenter.getFilesCount() == 0){
            showNoFilesMsg()
        }
        else{
            filesList.runOnLayoutChanged { setupToolBarScrollingBehavior(filesList.isScrollable) }
        }
    }

    override fun hideSwipeLayoutRefresher() {
        if(filesListRefresher.isRefreshing){
            App.get().handler.postDelayed({ filesListRefresher.isRefreshing = false }, 100)
        }
    }

    override fun getFilesListState(): Parcelable? = (filesList.layoutManager as LinearLayoutManager).onSaveInstanceState()

    override fun showToast(@StringRes messageId: Int) {
        Toast.makeText(context, getString(messageId), Toast.LENGTH_SHORT).show()
    }

    override fun showShareDialog(file: AbstractStorageFile) {
        Utils.showShareFileDialog(context!!, file)
    }

    override fun showOpenFileDialog(file: AbstractStorageFile) {
        Utils.showOpenFileDialog(context!!, file)
    }
}