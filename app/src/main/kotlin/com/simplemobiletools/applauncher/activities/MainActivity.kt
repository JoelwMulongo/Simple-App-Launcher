package com.simplemobiletools.applauncher.activities

import android.content.Intent
import android.os.Bundle
import com.simplemobiletools.applauncher.BuildConfig
import com.simplemobiletools.applauncher.R
import com.simplemobiletools.applauncher.adapters.LaunchersAdapter
import com.simplemobiletools.applauncher.dialogs.AddLaunchersDialog
import com.simplemobiletools.applauncher.dialogs.ChangeSortingDialog
import com.simplemobiletools.applauncher.extensions.config
import com.simplemobiletools.applauncher.extensions.dbHelper
import com.simplemobiletools.applauncher.extensions.getAllLaunchers
import com.simplemobiletools.applauncher.extensions.isAPredefinedApp
import com.simplemobiletools.applauncher.models.AppLauncher
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.interfaces.RefreshRecyclerViewListener
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.Release
import com.simplemobiletools.commons.views.MyGridLayoutManager
import com.simplemobiletools.commons.views.MyRecyclerView
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : SimpleActivity(), RefreshRecyclerViewListener {
    private val MAX_COLUMN_COUNT = 20

    private var displayedLaunchers = ArrayList<AppLauncher>()
    private var allLaunchers: ArrayList<AppLauncher>? = null
    private var zoomListener: MyRecyclerView.MyZoomListener? = null

    private var mStoredPrimaryColor = 0
    private var mStoredTextColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEmptyView()
        setupLaunchers()
        checkWhatsNewDialog()
        storeStateVariables()
        setupGridLayoutManager()

        fab.setOnClickListener {
            fabClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(main_toolbar)
        if (mStoredTextColor != getProperTextColor()) {
            getGridAdapter()?.updateTextColor(getProperTextColor())
        }

        val properPrimaryColor = getProperPrimaryColor()
        if (mStoredPrimaryColor != properPrimaryColor) {
            getGridAdapter()?.apply {
                updatePrimaryColor()
                notifyDataSetChanged()
            }
        }

        updateTextColors(coordinator_layout)
        add_icons_placeholder.setTextColor(properPrimaryColor)
        launchers_fastscroller.updateColors(properPrimaryColor)
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
    }

    private fun refreshMenuItems() {
        val currentColumnCount = if (portrait) {
            config.portraitColumnCnt
        } else {
            config.landscapeColumnCnt
        }

        main_toolbar.menu.apply {
            findItem(R.id.increase_column_count).isVisible = currentColumnCount < MAX_COLUMN_COUNT
            findItem(R.id.reduce_column_count).isVisible = currentColumnCount > 1
        }
    }

    private fun setupOptionsMenu() {
        main_toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> showSortingDialog()
                R.id.toggle_app_name -> toggleAppName()
                R.id.increase_column_count -> increaseColumnCount()
                R.id.reduce_column_count -> reduceColumnCount()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = 0L

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text),
        )

        if (!resources.getBoolean(R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons))
            faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, false)
    }

    private fun getGridAdapter() = launchers_grid.adapter as? LaunchersAdapter

    private fun setupLaunchers() {
        displayedLaunchers = dbHelper.getLaunchers()
        checkInvalidApps()
        initZoomListener()
        setupAdapter(displayedLaunchers)
        maybeShowEmptyView()
    }

    private fun setupAdapter(launchers: ArrayList<AppLauncher>) {
        AppLauncher.sorting = config.sorting
        launchers.sort()

        LaunchersAdapter(
            activity = this,
            launchers = launchers,
            listener = this,
            recyclerView = launchers_grid,
        ) {
            hideKeyboard()
            val launchIntent = packageManager.getLaunchIntentForPackage((it as AppLauncher).packageName)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    if (config.closeApp) {
                        finish()
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            } else {
                try {
                    launchViewIntent("market://details?id=${it.packageName}")
                } catch (ignored: Exception) {
                    launchViewIntent("https://play.google.com/store/apps/details?id=${it.packageName}")
                }
            }
        }.apply {
            setupZoomListener(zoomListener)
            launchers_grid.adapter = this
        }

        ensureBackgroundThread {
            allLaunchers = getAllLaunchers()
        }
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this) {
            setupAdapter(displayedLaunchers)
        }
    }

    private fun toggleAppName() {
        config.showAppName = !config.showAppName
        setupAdapter(displayedLaunchers)
    }

    private fun increaseColumnCount() {
        val newColumnCount = ++(launchers_grid.layoutManager as MyGridLayoutManager).spanCount
        if (portrait) {
            config.portraitColumnCnt = newColumnCount
        } else {
            config.landscapeColumnCnt = newColumnCount
        }
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        val newColumnCount = --(launchers_grid.layoutManager as MyGridLayoutManager).spanCount
        if (portrait) {
            config.portraitColumnCnt = newColumnCount
        } else {
            config.landscapeColumnCnt = newColumnCount
        }
        columnCountChanged()
    }

    private fun columnCountChanged() {
        refreshMenuItems()
        getGridAdapter()?.apply {
            calculateIconWidth()
            notifyItemRangeChanged(0, launchers.size)
        }
    }

    private fun setupGridLayoutManager() {
        val layoutManager = launchers_grid.layoutManager as MyGridLayoutManager
        if (portrait) {
            layoutManager.spanCount = config.portraitColumnCnt
        } else {
            layoutManager.spanCount = config.landscapeColumnCnt
        }
    }

    private fun initZoomListener() {
        val layoutManager = launchers_grid.layoutManager as MyGridLayoutManager
        zoomListener = object : MyRecyclerView.MyZoomListener {
            override fun zoomIn() {
                if (layoutManager.spanCount > 1) {
                    reduceColumnCount()
                    getGridAdapter()?.finishActMode()
                }
            }

            override fun zoomOut() {
                if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                    increaseColumnCount()
                    getGridAdapter()?.finishActMode()
                }
            }
        }
    }

    private fun checkInvalidApps() {
        val invalidIds = ArrayList<String>()
        for ((id, name, packageName) in displayedLaunchers) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null && !packageName.isAPredefinedApp()) {
                invalidIds.add(id.toString())
            }
        }

        dbHelper.deleteLaunchers(invalidIds)
        displayedLaunchers = displayedLaunchers.filter { !invalidIds.contains(it.id.toString()) } as ArrayList<AppLauncher>
    }

    private fun storeStateVariables() {
        mStoredPrimaryColor = getProperPrimaryColor()
        mStoredTextColor = getProperTextColor()
    }

    override fun refreshItems() {
        setupLaunchers()
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(7, R.string.release_7))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }

    private fun fabClicked() {
        if (allLaunchers != null) {
            val shownLaunchers = (launchers_grid.adapter as LaunchersAdapter).launchers
            AddLaunchersDialog(this, allLaunchers!!, shownLaunchers) {
                setupLaunchers()
            }
        }
    }

    private fun setupEmptyView() {
        val properPrimaryColor = getProperPrimaryColor()
        add_icons_placeholder.underlineText()
        add_icons_placeholder.setTextColor(properPrimaryColor)
        add_icons_placeholder.setOnClickListener {
            fabClicked()
        }
    }

    private fun maybeShowEmptyView() {
        val emptyViews = arrayOf(add_icons_placeholder, no_items_placeholder)
        if (displayedLaunchers.isEmpty()) {
            launchers_fastscroller.fadeOut()
            emptyViews.forEach { it.fadeIn() }
        } else {
            emptyViews.forEach { it.fadeOut() }
            launchers_fastscroller.fadeIn()
        }
    }
}
