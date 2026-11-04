package com.simplemobiletools.gallery.pro.activities

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore.Images
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import androidx.print.PrintHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.simplemobiletools.commons.dialogs.PropertiesDialog
import com.simplemobiletools.commons.dialogs.RenameItemDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.gallery.pro.BuildConfig
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.adapters.MyPagerAdapter
import com.simplemobiletools.gallery.pro.adapters.ViewerThumbnailStripAdapter
import com.simplemobiletools.gallery.pro.asynctasks.GetMediaAsynctask
import com.simplemobiletools.gallery.pro.databinding.ActivityMediumBinding
import com.simplemobiletools.gallery.pro.dialogs.DeleteWithRememberDialog
import com.simplemobiletools.gallery.pro.dialogs.SaveAsDialog
import com.simplemobiletools.gallery.pro.dialogs.SlideshowDialog
import com.simplemobiletools.gallery.pro.dialogs.ViewerThumbnailsDialog
import com.simplemobiletools.gallery.pro.extensions.*
import com.simplemobiletools.gallery.pro.fragments.PhotoFragment
import com.simplemobiletools.gallery.pro.fragments.VideoFragment
import com.simplemobiletools.gallery.pro.fragments.ViewPagerFragment
import com.simplemobiletools.gallery.pro.helpers.*
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.models.ThumbnailItem
import java.io.File
import kotlin.math.min

@Suppress("UNCHECKED_CAST")
class ViewPagerActivity : SimpleActivity(), ViewPager.OnPageChangeListener, ViewPagerFragment.FragmentListener {
    private val REQUEST_VIEW_VIDEO = 1

    private var mPath = ""
    private var mDirectory = ""
    // covers everything / strip only / image only, cycled by tapping the photo
    private var mViewerUiState = VIEWER_UI_ALL
    private var mIsFullScreen = false
    private var mPos = -1
    private var mShowAll = false
    private var mIsSlideshowActive = false
    private var mPrevHashcode = 0

    private var mSlideshowHandler = Handler()
    private var mSlideshowInterval = SLIDESHOW_DEFAULT_INTERVAL
    private var mSlideshowMoveBackwards = false
    private var mSlideshowMedia = mutableListOf<Medium>()
    private var mAreSlideShowMediaVisible = false
    private var mRandomSlideshowStopped = false

    private var mIsOrientationLocked = false

    private var mMediaFiles = ArrayList<Medium>()
    private var mFavoritePaths = ArrayList<String>()
    private var mIgnoredPaths = ArrayList<String>()

    private var mThumbnailStripAdapter: ViewerThumbnailStripAdapter? = null

    // true only between a user drag on the strip and the moment it settles
    private var mIsUserScrollingStrip = false

    // how far the strip was lifted above its resting place, in pixels
    private var mStripOffsetPx = 0f

    private val binding by viewBinding(ActivityMediumBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        showTransparentTop = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        refreshMenuItems()

        window.decorView.setBackgroundColor(getProperBackgroundColor())
        binding.topShadow.layoutParams.height = statusBarHeight + actionBarHeight
        checkNotchSupport()
        (MediaActivity.mMedia.clone() as ArrayList<ThumbnailItem>).filterIsInstanceTo(mMediaFiles, Medium::class.java)

        handlePermission(getPermissionToRequest()) {
            if (it) {
                initViewPager()
            } else {
                toast(com.simplemobiletools.commons.R.string.no_storage_permissions)
                finish()
            }
        }

        initFavorites()
    }

    override fun onResume() {
        super.onResume()
        if (!hasPermission(getPermissionToRequest())) {
            finish()
            return
        }

        if (config.bottomActions) {
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            setTranslucentNavigation()
        }

        initBottomActions()

        if (config.maxBrightness) {
            val attributes = window.attributes
            attributes.screenBrightness = 1f
            window.attributes = attributes
        }

        setupOrientation()
        refreshMenuItems()

        val filename = getCurrentMedium()?.name ?: mPath.getFilenameFromPath()
        binding.mediumViewerToolbar.title = filename
    }

    override fun onPause() {
        super.onPause()
        stopSlideshow()

        // hand the photo the user stopped at back to the grid, so it lands on the same spot
        if (!isExternalIntent()) {
            getCurrentPath().takeIf { it.isNotEmpty() }?.let { config.pendingGridScrollPath = it }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (intent.extras?.containsKey(IS_VIEW_INTENT) == true) {
            config.temporarilyShowHidden = false
        }

        if (config.isThirdPartyIntent) {
            config.isThirdPartyIntent = false

            if (intent.extras == null || isExternalIntent()) {
                mMediaFiles.clear()
            }
        }
    }

    fun refreshMenuItems() {
        val currentMedium = getCurrentMedium() ?: return
        currentMedium.isFavorite = mFavoritePaths.contains(currentMedium.path)
        val visibleBottomActions = if (config.bottomActions) config.visibleBottomActions else 0

        runOnUiThread {
            val rotationDegrees = getCurrentPhotoFragment()?.mCurrentRotationDegrees ?: 0
            binding.mediumViewerToolbar.menu.apply {
                findItem(R.id.menu_show_on_map).isVisible = visibleBottomActions and BOTTOM_ACTION_SHOW_ON_MAP == 0
                findItem(R.id.menu_slideshow).isVisible = visibleBottomActions and BOTTOM_ACTION_SLIDESHOW == 0
                findItem(R.id.menu_properties).isVisible = visibleBottomActions and BOTTOM_ACTION_PROPERTIES == 0
                findItem(R.id.menu_delete).isVisible = visibleBottomActions and BOTTOM_ACTION_DELETE == 0
                findItem(R.id.menu_share).isVisible = visibleBottomActions and BOTTOM_ACTION_SHARE == 0
                findItem(R.id.menu_edit).isVisible = visibleBottomActions and BOTTOM_ACTION_EDIT == 0 && !currentMedium.isSVG()
                findItem(R.id.menu_rename).isVisible = visibleBottomActions and BOTTOM_ACTION_RENAME == 0 && !currentMedium.getIsInRecycleBin()
                findItem(R.id.menu_rotate).isVisible = currentMedium.isImage() && visibleBottomActions and BOTTOM_ACTION_ROTATE == 0
                findItem(R.id.menu_set_as).isVisible = visibleBottomActions and BOTTOM_ACTION_SET_AS == 0
                findItem(R.id.menu_copy_to).isVisible = visibleBottomActions and BOTTOM_ACTION_COPY == 0
                findItem(R.id.menu_move_to).isVisible = visibleBottomActions and BOTTOM_ACTION_MOVE == 0
                findItem(R.id.menu_save_as).isVisible = rotationDegrees != 0
                findItem(R.id.menu_print).isVisible = currentMedium.isImage() || currentMedium.isRaw()
                findItem(R.id.menu_resize).isVisible = visibleBottomActions and BOTTOM_ACTION_RESIZE == 0 && currentMedium.isImage()
                findItem(R.id.menu_hide).isVisible =
                    (!isRPlus() || isExternalStorageManager()) && !currentMedium.isHidden() && visibleBottomActions and BOTTOM_ACTION_TOGGLE_VISIBILITY == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_unhide).isVisible =
                    (!isRPlus() || isExternalStorageManager()) && currentMedium.isHidden() && visibleBottomActions and BOTTOM_ACTION_TOGGLE_VISIBILITY == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_add_to_favorites).isVisible =
                    !currentMedium.isFavorite && visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_remove_from_favorites).isVisible =
                    currentMedium.isFavorite && visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_restore_file).isVisible = currentMedium.path.startsWith(recycleBinPath)
                findItem(R.id.menu_thumbnail_grid).isVisible = shouldShowThumbnailStrip()
                findItem(R.id.menu_create_shortcut).isVisible = isOreoPlus()
                findItem(R.id.menu_change_orientation).isVisible = rotationDegrees == 0 && visibleBottomActions and BOTTOM_ACTION_CHANGE_ORIENTATION == 0
                findItem(R.id.menu_change_orientation).icon = resources.getDrawable(getChangeOrientationIcon())
                findItem(R.id.menu_rotate).setShowAsAction(
                    if (rotationDegrees != 0) {
                        MenuItem.SHOW_AS_ACTION_ALWAYS
                    } else {
                        MenuItem.SHOW_AS_ACTION_IF_ROOM
                    }
                )
            }

            if (visibleBottomActions != 0) {
                updateBottomActionIcons(currentMedium)
            }
        }
    }

    private fun setupOptionsMenu() {
        (binding.mediumViewerAppbar.layoutParams as RelativeLayout.LayoutParams).topMargin = statusBarHeight
        binding.mediumViewerToolbar.apply {
            setTitleTextColor(Color.WHITE)
            overflowIcon = resources.getColoredDrawableWithColor(com.simplemobiletools.commons.R.drawable.ic_three_dots_vector, Color.WHITE)
            navigationIcon = resources.getColoredDrawableWithColor(com.simplemobiletools.commons.R.drawable.ic_arrow_left_vector, Color.WHITE)
        }

        updateMenuItemColors(binding.mediumViewerToolbar.menu, forceWhiteIcons = true)
        binding.mediumViewerToolbar.setOnMenuItemClickListener { menuItem ->
            if (getCurrentMedium() == null) {
                return@setOnMenuItemClickListener true
            }

            when (menuItem.itemId) {
                R.id.menu_set_as -> setAs(getCurrentPath())
                R.id.menu_slideshow -> initSlideshow()
                R.id.menu_copy_to -> checkMediaManagementAndCopy(true)
                R.id.menu_move_to -> moveFileTo()
                R.id.menu_open_with -> openPath(getCurrentPath(), true)
                R.id.menu_hide -> toggleFileVisibility(true)
                R.id.menu_unhide -> toggleFileVisibility(false)
                R.id.menu_share -> shareMediumPath(getCurrentPath())
                R.id.menu_delete -> checkDeleteConfirmation()
                R.id.menu_rename -> checkMediaManagementAndRename()
                R.id.menu_print -> printFile()
                R.id.menu_edit -> openEditor(getCurrentPath())
                R.id.menu_properties -> showProperties()
                R.id.menu_show_on_map -> showFileOnMap(getCurrentPath())
                R.id.menu_rotate_right -> rotateImage(90)
                R.id.menu_rotate_left -> rotateImage(-90)
                R.id.menu_rotate_one_eighty -> rotateImage(180)
                R.id.menu_add_to_favorites -> toggleFavorite()
                R.id.menu_remove_from_favorites -> toggleFavorite()
                R.id.menu_restore_file -> restoreFile()
                R.id.menu_force_portrait -> toggleOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                R.id.menu_force_landscape -> toggleOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                R.id.menu_default_orientation -> toggleOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                R.id.menu_save_as -> saveImageAs()
                R.id.menu_create_shortcut -> createShortcut()
                R.id.menu_resize -> resizeImage()
                R.id.menu_thumbnail_grid -> openThumbnailsGrid()
                R.id.menu_settings -> launchSettings()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }

        binding.mediumViewerToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_EDIT_IMAGE && resultCode == Activity.RESULT_OK && resultData != null) {
            mPos = -1
            mPrevHashcode = 0
            refreshViewPager()
        } else if (requestCode == REQUEST_SET_AS && resultCode == Activity.RESULT_OK) {
            toast(R.string.wallpaper_set_successfully)
        } else if (requestCode == REQUEST_VIEW_VIDEO && resultCode == Activity.RESULT_OK && resultData != null) {
            if (resultData.getBooleanExtra(GO_TO_NEXT_ITEM, false)) {
                goToNextItem()
            } else if (resultData.getBooleanExtra(GO_TO_PREV_ITEM, false)) {
                goToPrevItem()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initBottomActionsLayout()
        (binding.mediumViewerAppbar.layoutParams as RelativeLayout.LayoutParams).topMargin = statusBarHeight
    }

    private fun initViewPager() {
        val uri = intent.data
        if (uri != null) {
            var cursor: Cursor? = null
            try {
                val proj = arrayOf(Images.Media.DATA)
                cursor = contentResolver.query(uri, proj, null, null, null)
                if (cursor?.moveToFirst() == true) {
                    mPath = cursor.getStringValue(Images.Media.DATA)
                }
            } finally {
                cursor?.close()
            }
        } else {
            try {
                mPath = intent.getStringExtra(PATH) ?: ""

                // make sure "Open Recycle Bin" works well with "Show all folders content"
                mShowAll = config.showAll && (mPath.isNotEmpty() && !mPath.startsWith(recycleBinPath))
            } catch (e: Exception) {
                showErrorToast(e)
                finish()
                return
            }
        }

        if (intent.extras?.containsKey(REAL_FILE_PATH) == true) {
            mPath = intent.extras!!.getString(REAL_FILE_PATH)!!
        }

        if (mPath.isEmpty()) {
            toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
            finish()
            return
        }

        if (mPath.isPortrait() && getPortraitPath() == "") {
            val newIntent = Intent(this, ViewPagerActivity::class.java)
            newIntent.putExtras(intent!!.extras!!)
            newIntent.putExtra(PORTRAIT_PATH, mPath)
            newIntent.putExtra(PATH, "${mPath.getParentPath().getParentPath()}/${mPath.getFilenameFromPath()}")

            startActivity(newIntent)
            finish()
            return
        }

        if (!getDoesFilePathExist(mPath) && getPortraitPath() == "") {
            finish()
            return
        }

        showSystemUI(true)

        if (intent.getBooleanExtra(SKIP_AUTHENTICATION, false)) {
            initContinue()
        } else {
            handleLockedFolderOpening(mPath.getParentPath()) { success ->
                if (success) {
                    initContinue()
                } else {
                    finish()
                }
            }
        }
    }

    private fun initContinue() {
        if (intent.extras?.containsKey(IS_VIEW_INTENT) == true) {
            if (isShowHiddenFlagNeeded()) {
                if (!config.isHiddenPasswordProtectionOn) {
                    config.temporarilyShowHidden = true
                }
            }

            config.isThirdPartyIntent = true
        }

        val isShowingFavorites = intent.getBooleanExtra(SHOW_FAVORITES, false)
        val isShowingRecycleBin = intent.getBooleanExtra(SHOW_RECYCLE_BIN, false)
        mDirectory = when {
            isShowingFavorites -> FAVORITES
            isShowingRecycleBin -> RECYCLE_BIN
            else -> mPath.getParentPath()
        }
        binding.mediumViewerToolbar.title = mPath.getFilenameFromPath()

        binding.viewPager.onGlobalLayout {
            if (!isDestroyed) {
                if (mMediaFiles.isNotEmpty()) {
                    gotMedia(mMediaFiles as ArrayList<ThumbnailItem>, refetchViewPagerPosition = true)
                    checkSlideshowOnEnter()
                }
            }
        }

        // show the selected image asap, while loading the rest in the background to allow swiping between them. Might be needed at third party intents
        if (mMediaFiles.isEmpty() && mPath.isNotEmpty() && mDirectory != FAVORITES) {
            val filename = mPath.getFilenameFromPath()
            val folder = mPath.getParentPath()
            val type = getTypeFromPath(mPath)
            val medium = Medium(null, filename, mPath, folder, 0, 0, 0, type, 0, false, 0L, 0L)
            mMediaFiles.add(medium)
            gotMedia(mMediaFiles as ArrayList<ThumbnailItem>, refetchViewPagerPosition = true)
        }

        refreshViewPager(true)
        binding.viewPager.offscreenPageLimit = 2

        if (config.blackBackground) {
            binding.viewPager.background = ColorDrawable(Color.BLACK)
        }

        if (config.hideSystemUI) {
            binding.viewPager.onGlobalLayout {
                Handler().postDelayed({
                    fragmentClicked()
                }, HIDE_SYSTEM_UI_DELAY)
            }
        }

        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            val isFullScreenNow = if (isNougatPlus() && isInMultiWindowMode) {
                visibility and View.SYSTEM_UI_FLAG_LOW_PROFILE != 0
            } else if (visibility and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0) {
                false
            } else {
                visibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
            }
            mIsFullScreen = isFullScreenNow

            // the system bars appearing or going away by themselves: snap to the closest step
            val newState = if (isFullScreenNow) {
                if (mViewerUiState == VIEWER_UI_ALL) VIEWER_UI_NO_TOOLBARS else mViewerUiState
            } else {
                VIEWER_UI_ALL
            }

            if (newState != mViewerUiState) {
                mViewerUiState = newState
                checkSystemUI()
                fullscreenToggled()
            }
        }

        if (intent.action == "com.android.camera.action.REVIEW") {
            ensureBackgroundThread {
                if (mediaDB.getMediaFromPath(mPath).isEmpty()) {
                    val filename = mPath.getFilenameFromPath()
                    val parent = mPath.getParentPath()
                    val type = getTypeFromPath(mPath)
                    val isFavorite = favoritesDB.isFavorite(mPath)
                    val duration = if (type == TYPE_VIDEOS) getDuration(mPath) ?: 0 else 0
                    val ts = System.currentTimeMillis()
                    val medium = Medium(null, filename, mPath, parent, ts, ts, File(mPath).length(), type, duration, isFavorite, 0, 0L)
                    mediaDB.insert(medium)
                }
            }
        }
    }

    private fun getTypeFromPath(path: String): Int {
        return when {
            path.isVideoFast() -> TYPE_VIDEOS
            path.isGif() -> TYPE_GIFS
            path.isSvg() -> TYPE_SVGS
            path.isRawFast() -> TYPE_RAWS
            path.isPortrait() -> TYPE_PORTRAITS
            else -> TYPE_IMAGES
        }
    }

    private fun initBottomActions() {
        initBottomActionButtons()
        initBottomActionsLayout()
        setupThumbnailStrip()
    }

    /**
     * Bottom strip of thumbnails, the horizontal sibling of the pager. Scrolling it
     * flips the page like the iOS gallery does. A long press turns the strip itself
     * into a movable handle, so it can be lifted away from the bottom actions.
     */
    private fun setupThumbnailStrip() {
        val strip = binding.viewerThumbnailStrip
        val wrapper = binding.viewerThumbnailStripWrapper
        val showStrip = shouldShowThumbnailStrip()
        wrapper.beVisibleIf(showStrip && mViewerUiState != VIEWER_UI_IMAGE_ONLY)
        binding.viewerThumbnailStripHandle.setOnClickListener { openThumbnailsGrid() }

        mStripOffsetPx = (config.viewerThumbnailStripOffset * resources.displayMetrics.density).toFloat()
        wrapper.onDrag = { dy -> moveThumbnailStrip(-dy) }
        wrapper.onDragEnd = { config.viewerThumbnailStripOffset = (mStripOffsetPx / resources.displayMetrics.density).toInt() }
        wrapper.onSwipeUp = { openThumbnailsGrid() }

        if (!showStrip) {
            mThumbnailStripAdapter = null
            strip.adapter = null
            strip.clearOnScrollListeners()
            updatePageIndicator()
            return
        }

        val currentItem = binding.viewPager.currentItem.coerceAtLeast(0)
        val adapter = ViewerThumbnailStripAdapter(this, mMediaFiles.toList()) { position -> selectPage(position) }
        adapter.thumbnailShape = config.viewerThumbnailShape
        adapter.currentSelectionIndex = currentItem
        adapter.recyclerView = strip
        mThumbnailStripAdapter = adapter

        strip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        strip.adapter = adapter
        strip.clearOnScrollListeners()
        strip.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // while the user drags (or lets it fling), the big photo follows the finger.
                // we deliberately do not re-center the strip here, it would fight with the drag
                if (!mIsUserScrollingStrip) {
                    return
                }
                val centered = getCenteredThumbnailPosition()
                if (centered == -1 || centered == binding.viewPager.currentItem) {
                    return
                }
                selectPage(centered)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    mIsUserScrollingStrip = true
                    return
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !mIsUserScrollingStrip) {
                    return
                }

                // the finger is off and the strip simply stays where it stopped, like pixez does.
                // pulling the closest thumbnail back to the middle on every release is what made
                // the scrolling feel like it was fighting the finger
                mIsUserScrollingStrip = false
            }
        })

        // enough padding on both ends so the very first and very last thumbnail can reach the middle
        strip.post {
            val gap = resources.getDimension(R.dimen.viewer_thumbnail_strip_gap).toInt()
            val padding = ((strip.width - adapter.itemWidth - gap) / 2).coerceAtLeast(0)
            strip.setPadding(padding, strip.paddingTop, padding, strip.paddingBottom)
            centerThumbnail(currentItem, false, 2)
        }
        updatePageIndicator()
    }

    private fun shouldShowThumbnailStrip() = config.viewerThumbnailBar && mMediaFiles.size > 1

    /**
     * Long press on the strip and drag up or down. The offset is kept in pixels while the
     * gesture runs and only written to the prefs once the finger is off, so the dragged
     * position survives reopening the viewer.
     */
    private fun moveThumbnailStrip(up: Float) {
        val maxOffset = (binding.fragmentHolder.height * 0.45f).coerceAtLeast(0f)
        mStripOffsetPx = (mStripOffsetPx + up).coerceIn(0f, maxOffset)
        layoutViewerOverlays()
    }

    /** Positions the thumbnail strip. The page number never moves, so it is not part of this. */
    private fun layoutViewerOverlays() {
        val bottomBase = if (config.bottomActions) {
            resources.getDimension(R.dimen.bottom_actions_height).toInt() + navigationBarHeight
        } else {
            navigationBarHeight
        }
        val stripMargin = resources.getDimension(R.dimen.viewer_thumbnail_strip_margin).toInt()
        val stripBottom = bottomBase + stripMargin + mStripOffsetPx.toInt()

        val strip = binding.viewerThumbnailStripWrapper
        val stripParams = strip.layoutParams as RelativeLayout.LayoutParams
        if (stripParams.bottomMargin != stripBottom) {
            stripParams.bottomMargin = stripBottom
            strip.layoutParams = stripParams
        }
    }

    /**
     * The page number stays glued to the bottom of the photo no matter what the strip does,
     * so it is laid out once instead of following every drag.
     */
    private fun layoutPageIndicator() {
        val bottomBase = if (config.bottomActions) {
            resources.getDimension(R.dimen.bottom_actions_height).toInt() + navigationBarHeight
        } else {
            navigationBarHeight
        }
        val gap = resources.getDimension(R.dimen.viewer_page_indicator_gap).toInt()

        val indicator = binding.viewerPageIndicator
        val params = indicator.layoutParams as RelativeLayout.LayoutParams
        if (params.bottomMargin != bottomBase + gap) {
            params.bottomMargin = bottomBase + gap
            indicator.layoutParams = params
        }
    }

    /** The thumbnail that currently sits in the middle of the strip. */
    private fun getCenteredThumbnailPosition(): Int {
        val recyclerView = binding.viewerThumbnailStrip
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return -1
        if (layoutManager.childCount == 0) {
            return -1
        }

        val center = recyclerView.width / 2
        var closestPosition = -1
        var closestDistance = Int.MAX_VALUE
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val childCenter = (child.left + child.right) / 2
            val distance = kotlin.math.abs(childCenter - center)
            if (distance < closestDistance) {
                closestDistance = distance
                closestPosition = layoutManager.getPosition(child)
            }
        }
        return closestPosition
    }

    private fun centerThumbnail(position: Int, smooth: Boolean = true, retries: Int = 1) {
        val recyclerView = binding.viewerThumbnailStrip
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (position !in mMediaFiles.indices) {
            return
        }

        val itemView = layoutManager.findViewByPosition(position)
        if (itemView == null) {
            // not laid out yet, bring it roughly into view and try again on the next frame
            if (retries <= 0) {
                return
            }
            layoutManager.scrollToPosition(position)
            recyclerView.post { centerThumbnail(position, smooth, retries - 1) }
            return
        }

        val delta = (itemView.left + itemView.right) / 2 - recyclerView.width / 2
        if (delta == 0) {
            return
        }
        if (smooth) {
            recyclerView.smoothScrollBy(delta, 0)
        } else {
            recyclerView.scrollBy(delta, 0)
        }
    }

    private fun selectPage(position: Int) {
        if (position !in mMediaFiles.indices || position == binding.viewPager.currentItem) {
            return
        }
        binding.viewPager.setCurrentItem(position, false)
    }

    private fun openThumbnailsGrid() {
        if (mMediaFiles.size <= 1) {
            return
        }

        val currentItem = binding.viewPager.currentItem.coerceAtLeast(0)
        ViewerThumbnailsDialog(this, mMediaFiles.toList(), currentItem) { position ->
            selectPage(position)
            centerThumbnail(position, false)
        }.apply {
            // the shape can be switched inside the grid, keep the strip in sync
            setOnDismissListener { mThumbnailStripAdapter?.updateShape(config.viewerThumbnailShape) }
        }.show()
    }

    private fun updatePageIndicator() {
        val indicator = binding.viewerPageIndicator
        val count = mMediaFiles.size
        val show = shouldShowPageIndicator()
        indicator.beVisibleIf(show)
        if (show) {
            indicator.text = "${(mPos + 1).coerceIn(1, count)}/$count"
        }
        layoutPageIndicator()
        layoutViewerOverlays()
    }

    private fun initFavorites() {
        ensureBackgroundThread {
            mFavoritePaths = getFavoritePaths()
        }
    }

    private fun setupOrientation() {
        if (!mIsOrientationLocked) {
            if (config.screenRotation == ROTATE_BY_DEVICE_ROTATION) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            } else if (config.screenRotation == ROTATE_BY_SYSTEM_SETTING) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun updatePagerItems(media: MutableList<Medium>) {
        val pagerAdapter = MyPagerAdapter(this, supportFragmentManager, media)
        if (!isDestroyed) {
            pagerAdapter.shouldInitFragment = mPos < 5
            binding.viewPager.apply {
                // must remove the listener before changing adapter, otherwise it might cause `mPos` to be set to 0
                removeOnPageChangeListener(this@ViewPagerActivity)
                adapter = pagerAdapter
                pagerAdapter.shouldInitFragment = true
                addOnPageChangeListener(this@ViewPagerActivity)
                currentItem = mPos
            }
        }
    }

    private fun checkSlideshowOnEnter() {
        if (intent.getBooleanExtra(SLIDESHOW_START_ON_ENTER, false)) {
            initSlideshow()
        }
    }

    private fun initSlideshow() {
        SlideshowDialog(this) {
            startSlideshow()
        }
    }

    private fun startSlideshow() {
        if (getMediaForSlideshow()) {
            binding.viewPager.onGlobalLayout {
                if (!isDestroyed) {
                    if (config.slideshowAnimation == SLIDESHOW_ANIMATION_FADE) {
                        binding.viewPager.setPageTransformer(false, FadePageTransformer())
                    }

                    hideSystemUI(true)
                    mRandomSlideshowStopped = false
                    mSlideshowInterval = config.slideshowInterval
                    mSlideshowMoveBackwards = config.slideshowMoveBackwards
                    mIsSlideshowActive = true
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    scheduleSwipe()
                }
            }
        }
    }

    private fun goToNextMedium(forward: Boolean) {
        val oldPosition = binding.viewPager.currentItem
        val newPosition = if (forward) oldPosition + 1 else oldPosition - 1
        if (newPosition == -1 || newPosition > binding.viewPager.adapter!!.count - 1) {
            slideshowEnded(forward)
        } else {
            binding.viewPager.setCurrentItem(newPosition, false)
        }
    }

    private fun animatePagerTransition(forward: Boolean) {
        val oldPosition = binding.viewPager.currentItem
        val animator = ValueAnimator.ofInt(0, binding.viewPager.width)
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(animation: Animator) {
                if (binding.viewPager.isFakeDragging) {
                    try {
                        binding.viewPager.endFakeDrag()
                    } catch (ignored: Exception) {
                        stopSlideshow()
                    }

                    if (binding.viewPager.currentItem == oldPosition) {
                        slideshowEnded(forward)
                    }
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                binding.viewPager.endFakeDrag()
            }

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationRepeat(animation: Animator) {}
        })

        if (config.slideshowAnimation == SLIDESHOW_ANIMATION_SLIDE) {
            animator.interpolator = DecelerateInterpolator()
            animator.duration = SLIDESHOW_SLIDE_DURATION
        } else {
            animator.duration = SLIDESHOW_FADE_DURATION
        }

        animator.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
            var oldDragPosition = 0
            override fun onAnimationUpdate(animation: ValueAnimator) {
                if (binding.viewPager?.isFakeDragging == true) {
                    val dragPosition = animation.animatedValue as Int
                    val dragOffset = dragPosition - oldDragPosition
                    oldDragPosition = dragPosition
                    try {
                        binding.viewPager.fakeDragBy(dragOffset * (if (forward) -1f else 1f))
                    } catch (e: Exception) {
                        stopSlideshow()
                    }
                }
            }
        })

        binding.viewPager.beginFakeDrag()
        animator.start()
    }

    private fun slideshowEnded(forward: Boolean) {
        if (config.loopSlideshow) {
            if (forward) {
                binding.viewPager.setCurrentItem(0, false)
            } else {
                binding.viewPager.setCurrentItem(binding.viewPager.adapter!!.count - 1, false)
            }
        } else {
            stopSlideshow()
            toast(R.string.slideshow_ended)
        }
    }

    private fun stopSlideshow() {
        if (mIsSlideshowActive) {
            binding.viewPager.setPageTransformer(false, DefaultPageTransformer())
            mIsSlideshowActive = false
            showSystemUI(true)
            mSlideshowHandler.removeCallbacksAndMessages(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            mAreSlideShowMediaVisible = false

            if (config.slideshowRandomOrder) {
                mRandomSlideshowStopped = true
            }
        }
    }

    private fun scheduleSwipe() {
        mSlideshowHandler.removeCallbacksAndMessages(null)
        if (mIsSlideshowActive) {
            if (getCurrentMedium()!!.isImage() || getCurrentMedium()!!.isGIF() || getCurrentMedium()!!.isPortrait()) {
                mSlideshowHandler.postDelayed({
                    if (mIsSlideshowActive && !isDestroyed) {
                        swipeToNextMedium()
                    }
                }, mSlideshowInterval * 1000L)
            } else {
                (getCurrentFragment() as? VideoFragment)!!.playVideo()
            }
        }
    }

    private fun swipeToNextMedium() {
        if (config.slideshowAnimation == SLIDESHOW_ANIMATION_NONE) {
            goToNextMedium(!mSlideshowMoveBackwards)
        } else {
            animatePagerTransition(!mSlideshowMoveBackwards)
        }
    }

    private fun getMediaForSlideshow(): Boolean {
        mSlideshowMedia = mMediaFiles.filter {
            it.isImage() || it.isPortrait() || (config.slideshowIncludeVideos && it.isVideo() || (config.slideshowIncludeGIFs && it.isGIF()))
        }.toMutableList()

        if (config.slideshowRandomOrder) {
            mSlideshowMedia.shuffle()
            mPos = 0
        } else {
            mPath = getCurrentPath()
            mPos = getPositionInList(mSlideshowMedia)
        }

        return if (mSlideshowMedia.isEmpty()) {
            toast(R.string.no_media_for_slideshow)
            false
        } else {
            updatePagerItems(mSlideshowMedia)
            mAreSlideShowMediaVisible = true
            true
        }
    }

    private fun moveFileTo() {
        handleDeletePasswordProtection {
            checkMediaManagementAndCopy(false)
        }
    }

    private fun checkMediaManagementAndCopy(isCopyOperation: Boolean) {
        handleMediaManagementPrompt {
            copyMoveTo(isCopyOperation)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val currPath = getCurrentPath()
        if (!isCopyOperation && currPath.startsWith(recycleBinPath)) {
            toast(com.simplemobiletools.commons.R.string.moving_recycle_bin_items_disabled, Toast.LENGTH_LONG)
            return
        }

        val fileDirItems = arrayListOf(FileDirItem(currPath, currPath.getFilenameFromPath()))
        tryCopyMoveFilesTo(fileDirItems, isCopyOperation) {
            val newPath = "$it/${currPath.getFilenameFromPath()}"
            rescanPaths(arrayListOf(newPath)) {
                fixDateTaken(arrayListOf(newPath), false)
            }

            config.tempFolderPath = ""
            if (!isCopyOperation) {
                refreshViewPager()
                updateFavoritePaths(fileDirItems, it)
            }
        }
    }

    private fun toggleFileVisibility(hide: Boolean, callback: (() -> Unit)? = null) {
        toggleFileVisibility(getCurrentPath(), hide) {
            val newFileName = it.getFilenameFromPath()
            binding.mediumViewerToolbar.title = newFileName

            getCurrentMedium()!!.apply {
                name = newFileName
                path = it
                getCurrentMedia()[mPos] = this
            }

            refreshMenuItems()
            callback?.invoke()
        }
    }

    private fun rotateImage(degrees: Int) {
        val currentPath = getCurrentPath()
        if (needsStupidWritePermissions(currentPath)) {
            handleSAFDialog(currentPath) {
                if (it) {
                    rotateBy(degrees)
                }
            }
        } else {
            rotateBy(degrees)
        }
    }

    private fun rotateBy(degrees: Int) {
        getCurrentPhotoFragment()?.rotateImageViewBy(degrees)
        refreshMenuItems()
    }

    private fun toggleOrientation(orientation: Int) {
        requestedOrientation = orientation
        mIsOrientationLocked = orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        refreshMenuItems()
    }

    private fun getChangeOrientationIcon(): Int {
        return if (mIsOrientationLocked) {
            if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                com.simplemobiletools.commons.R.drawable.ic_orientation_portrait_vector
            } else {
                com.simplemobiletools.commons.R.drawable.ic_orientation_landscape_vector
            }
        } else {
            com.simplemobiletools.commons.R.drawable.ic_orientation_auto_vector
        }
    }

    private fun saveImageAs() {
        val currPath = getCurrentPath()
        SaveAsDialog(this, currPath, false) {
            val newPath = it
            handleSAFDialog(it) {
                if (!it) {
                    return@handleSAFDialog
                }

                toast(com.simplemobiletools.commons.R.string.saving)
                ensureBackgroundThread {
                    val photoFragment = getCurrentPhotoFragment() ?: return@ensureBackgroundThread
                    saveRotatedImageToFile(currPath, newPath, photoFragment.mCurrentRotationDegrees, true) {
                        toast(com.simplemobiletools.commons.R.string.file_saved)
                        getCurrentPhotoFragment()?.mCurrentRotationDegrees = 0
                        refreshMenuItems()
                    }
                }
            }
        }
    }

    private fun createShortcut() {
        if (!isOreoPlus()) {
            return
        }

        val manager = getSystemService(ShortcutManager::class.java)
        if (manager.isRequestPinShortcutSupported) {
            val medium = getCurrentMedium() ?: return
            val path = medium.path
            val drawable = resources.getDrawable(R.drawable.shortcut_image).mutate()
            getShortcutImage(path, drawable) {
                val intent = Intent(this, ViewPagerActivity::class.java).apply {
                    putExtra(PATH, path)
                    putExtra(SHOW_ALL, config.showAll)
                    putExtra(SHOW_FAVORITES, path == FAVORITES)
                    putExtra(SHOW_RECYCLE_BIN, path == RECYCLE_BIN)
                    action = Intent.ACTION_VIEW
                    flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val shortcut = ShortcutInfo.Builder(this, path)
                    .setShortLabel(medium.name)
                    .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
            }
        }
    }

    private fun getCurrentPhotoFragment() = getCurrentFragment() as? PhotoFragment

    private fun getPortraitPath() = intent.getStringExtra(PORTRAIT_PATH) ?: ""

    private fun isShowHiddenFlagNeeded(): Boolean {
        val file = File(mPath)
        if (file.isHidden) {
            return true
        }

        var parent = file.parentFile ?: return false
        while (true) {
            if (parent.isHidden || parent.list()?.any { it.startsWith(NOMEDIA) } == true) {
                return true
            }

            if (parent.absolutePath == "/") {
                break
            }
            parent = parent.parentFile ?: return false
        }

        return false
    }

    private fun getCurrentFragment() = (binding.viewPager.adapter as? MyPagerAdapter)?.getCurrentFragment(binding.viewPager.currentItem)

    private fun showProperties() {
        if (getCurrentMedium() != null) {
            PropertiesDialog(this, getCurrentPath(), false)
        }
    }

    private fun initBottomActionsLayout() {
        binding.bottomActions.root.layoutParams.height = resources.getDimension(R.dimen.bottom_actions_height).toInt() + navigationBarHeight
        if (config.bottomActions) {
            binding.bottomActions.root.beVisible()
        } else {
            binding.bottomActions.root.beGone()
        }

        // the strip sits above whatever is at the very bottom, the page number above the strip
        layoutViewerOverlays()

        if (!portrait && navigationBarOnSide && navigationBarWidth > 0) {
            binding.mediumViewerToolbar.setPadding(0, 0, navigationBarWidth, 0)
        } else {
            binding.mediumViewerToolbar.setPadding(0, 0, 0, 0)
        }
    }

    private fun initBottomActionButtons() {
        val currentMedium = getCurrentMedium()
        val visibleBottomActions = if (config.bottomActions) config.visibleBottomActions else 0
        binding.bottomActions.bottomFavorite.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE != 0 && currentMedium?.getIsInRecycleBin() == false)
        binding.bottomActions.bottomFavorite.setOnLongClickListener { toast(R.string.toggle_favorite); true }
        binding.bottomActions.bottomFavorite.setOnClickListener {
            toggleFavorite()
        }

        binding.bottomActions.bottomEdit.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_EDIT != 0 && currentMedium?.isSVG() == false)
        binding.bottomActions.bottomEdit.setOnLongClickListener { toast(R.string.edit); true }
        binding.bottomActions.bottomEdit.setOnClickListener {
            openEditor(getCurrentPath())
        }

        binding.bottomActions.bottomShare.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_SHARE != 0)
        binding.bottomActions.bottomShare.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.share); true }
        binding.bottomActions.bottomShare.setOnClickListener {
            shareMediumPath(getCurrentPath())
        }

        binding.bottomActions.bottomDelete.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_DELETE != 0)
        binding.bottomActions.bottomDelete.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.delete); true }
        binding.bottomActions.bottomDelete.setOnClickListener {
            checkDeleteConfirmation()
        }

        binding.bottomActions.bottomRotate.beVisibleIf(config.visibleBottomActions and BOTTOM_ACTION_ROTATE != 0 && getCurrentMedium()?.isImage() == true)
        binding.bottomActions.bottomRotate.setOnLongClickListener { toast(R.string.rotate); true }
        binding.bottomActions.bottomRotate.setOnClickListener {
            rotateImage(90)
        }

        binding.bottomActions.bottomProperties.applyColorFilter(Color.WHITE)
        binding.bottomActions.bottomProperties.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_PROPERTIES != 0)
        binding.bottomActions.bottomProperties.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.properties); true }
        binding.bottomActions.bottomProperties.setOnClickListener {
            showProperties()
        }

        binding.bottomActions.bottomChangeOrientation.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_CHANGE_ORIENTATION != 0)
        binding.bottomActions.bottomChangeOrientation.setOnLongClickListener { toast(R.string.change_orientation); true }
        binding.bottomActions.bottomChangeOrientation.setOnClickListener {
            requestedOrientation = when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            mIsOrientationLocked = requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            updateBottomActionIcons(currentMedium)
        }

        binding.bottomActions.bottomSlideshow.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_SLIDESHOW != 0)
        binding.bottomActions.bottomSlideshow.setOnLongClickListener { toast(R.string.slideshow); true }
        binding.bottomActions.bottomSlideshow.setOnClickListener {
            initSlideshow()
        }

        binding.bottomActions.bottomShowOnMap.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_SHOW_ON_MAP != 0)
        binding.bottomActions.bottomShowOnMap.setOnLongClickListener { toast(R.string.show_on_map); true }
        binding.bottomActions.bottomShowOnMap.setOnClickListener {
            showFileOnMap(getCurrentPath())
        }

        binding.bottomActions.bottomToggleFileVisibility.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_TOGGLE_VISIBILITY != 0)
        binding.bottomActions.bottomToggleFileVisibility.setOnLongClickListener {
            toast(if (currentMedium?.isHidden() == true) com.simplemobiletools.commons.R.string.unhide else com.simplemobiletools.commons.R.string.hide); true
        }

        binding.bottomActions.bottomToggleFileVisibility.setOnClickListener {
            currentMedium?.apply {
                toggleFileVisibility(!isHidden()) {
                    updateBottomActionIcons(currentMedium)
                }
            }
        }

        binding.bottomActions.bottomRename.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_RENAME != 0 && currentMedium?.getIsInRecycleBin() == false)
        binding.bottomActions.bottomRename.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.rename); true }
        binding.bottomActions.bottomRename.setOnClickListener {
            checkMediaManagementAndRename()
        }

        binding.bottomActions.bottomSetAs.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_SET_AS != 0)
        binding.bottomActions.bottomSetAs.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.set_as); true }
        binding.bottomActions.bottomSetAs.setOnClickListener {
            setAs(getCurrentPath())
        }

        binding.bottomActions.bottomCopy.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_COPY != 0)
        binding.bottomActions.bottomCopy.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.copy); true }
        binding.bottomActions.bottomCopy.setOnClickListener {
            checkMediaManagementAndCopy(true)
        }

        binding.bottomActions.bottomMove.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_MOVE != 0)
        binding.bottomActions.bottomMove.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.move); true }
        binding.bottomActions.bottomMove.setOnClickListener {
            moveFileTo()
        }

        binding.bottomActions.bottomResize.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_RESIZE != 0 && currentMedium?.isImage() == true)
        binding.bottomActions.bottomResize.setOnLongClickListener { toast(com.simplemobiletools.commons.R.string.resize); true }
        binding.bottomActions.bottomResize.setOnClickListener {
            resizeImage()
        }
    }

    private fun updateBottomActionIcons(medium: Medium?) {
        if (medium == null) {
            return
        }

        val favoriteIcon =
            if (medium.isFavorite) com.simplemobiletools.commons.R.drawable.ic_star_vector else com.simplemobiletools.commons.R.drawable.ic_star_outline_vector
        binding.bottomActions.bottomFavorite.setImageResource(favoriteIcon)

        val hideIcon =
            if (medium.isHidden()) com.simplemobiletools.commons.R.drawable.ic_unhide_vector else com.simplemobiletools.commons.R.drawable.ic_hide_vector
        binding.bottomActions.bottomToggleFileVisibility.setImageResource(hideIcon)

        binding.bottomActions.bottomRotate.beVisibleIf(config.visibleBottomActions and BOTTOM_ACTION_ROTATE != 0 && getCurrentMedium()?.isImage() == true)
        binding.bottomActions.bottomChangeOrientation.setImageResource(getChangeOrientationIcon())
    }

    private fun toggleFavorite() {
        val medium = getCurrentMedium() ?: return
        medium.isFavorite = !medium.isFavorite
        ensureBackgroundThread {
            updateFavorite(medium.path, medium.isFavorite)
            if (medium.isFavorite) {
                mFavoritePaths.add(medium.path)
            } else {
                mFavoritePaths.remove(medium.path)
            }

            runOnUiThread {
                refreshMenuItems()
            }
        }
    }

    private fun printFile() {
        sendPrintIntent(getCurrentPath())
    }

    private fun sendPrintIntent(path: String) {
        val printHelper = PrintHelper(this)
        printHelper.scaleMode = PrintHelper.SCALE_MODE_FIT
        printHelper.orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        try {
            val resolution = path.getImageResolution(this)
            if (resolution == null) {
                toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                return
            }

            var requestedWidth = resolution.x
            var requestedHeight = resolution.y

            if (requestedWidth >= MAX_PRINT_SIDE_SIZE) {
                requestedHeight = (requestedHeight / (requestedWidth / MAX_PRINT_SIDE_SIZE.toFloat())).toInt()
                requestedWidth = MAX_PRINT_SIDE_SIZE
            } else if (requestedHeight >= MAX_PRINT_SIDE_SIZE) {
                requestedWidth = (requestedWidth / (requestedHeight / MAX_PRINT_SIDE_SIZE.toFloat())).toInt()
                requestedHeight = MAX_PRINT_SIDE_SIZE
            }

            val options = RequestOptions()
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)

            Glide.with(this)
                .asBitmap()
                .load(path)
                .apply(options)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                        showErrorToast(e?.localizedMessage ?: "")
                        return false
                    }

                    override fun onResourceReady(
                        bitmap: Bitmap,
                        model: Any,
                        target: Target<Bitmap>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        printHelper.printBitmap(path.getFilenameFromPath(), bitmap)
                        return false
                    }
                }).submit(requestedWidth, requestedHeight)
        } catch (e: Exception) {
        }
    }

    private fun restoreFile() {
        restoreRecycleBinPath(getCurrentPath()) {
            refreshViewPager()
        }
    }

    private fun resizeImage() {
        val oldPath = getCurrentPath()
        launchResizeImageDialog(oldPath)
    }

    private fun checkDeleteConfirmation() {
        if (getCurrentMedium() == null) {
            return
        }

        handleMediaManagementPrompt {
            if (config.isDeletePasswordProtectionOn) {
                handleDeletePasswordProtection {
                    deleteConfirmed(config.tempSkipRecycleBin)
                }
            } else if (config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation) {
                deleteConfirmed(config.tempSkipRecycleBin)
            } else {
                askConfirmDelete()
            }
        }
    }

    private fun askConfirmDelete() {
        val fileDirItem = getCurrentMedium()?.toFileDirItem() ?: return
        val size = fileDirItem.getProperSize(this, countHidden = true).formatSize()
        val filename = "\"${getCurrentPath().getFilenameFromPath()}\""
        val filenameAndSize = "$filename ($size)"
        val isInRecycleBin = getCurrentMedium()!!.getIsInRecycleBin()

        val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin && !isInRecycleBin) {
            com.simplemobiletools.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            com.simplemobiletools.commons.R.string.deletion_confirmation
        }

        val message = String.format(resources.getString(baseString), filenameAndSize)
        val showSkipRecycleBinOption = config.useRecycleBin && !isInRecycleBin

        DeleteWithRememberDialog(this, message, showSkipRecycleBinOption) { remember, skipRecycleBin ->
            config.tempSkipDeleteConfirmation = remember

            if (remember) {
                config.tempSkipRecycleBin = skipRecycleBin
            }

            deleteConfirmed(skipRecycleBin)
        }
    }

    private fun deleteConfirmed(skipRecycleBin: Boolean) {
        val currentMedium = getCurrentMedium()
        val path = currentMedium?.path ?: return
        if (getIsPathDirectory(path) || !path.isMediaFile()) {
            return
        }

        val fileDirItem = currentMedium.toFileDirItem()
        if (config.useRecycleBin && !skipRecycleBin && !getCurrentMedium()!!.getIsInRecycleBin()) {
            checkManageMediaOrHandleSAFDialogSdk30(fileDirItem.path) {
                if (!it) {
                    return@checkManageMediaOrHandleSAFDialogSdk30
                }

                mIgnoredPaths.add(fileDirItem.path)
                val media = mMediaFiles.filter { !mIgnoredPaths.contains(it.path) } as ArrayList<Medium>
                if (media.isNotEmpty()) {
                    runOnUiThread {
                        refreshUI(media, false)
                    }
                }

                if (media.size == 1) {
                    onPageSelected(0)
                }

                movePathsInRecycleBin(arrayListOf(path)) {
                    if (it) {
                        tryDeleteFileDirItem(fileDirItem, false, false) {
                            mIgnoredPaths.remove(fileDirItem.path)
                            if (media.isEmpty()) {
                                deleteDirectoryIfEmpty()
                                finish()
                            }
                        }
                    } else {
                        toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                    }
                }
            }
        } else {
            handleDeletion(fileDirItem)
        }
    }

    private fun handleDeletion(fileDirItem: FileDirItem) {
        checkManageMediaOrHandleSAFDialogSdk30(fileDirItem.path) {
            if (!it) {
                return@checkManageMediaOrHandleSAFDialogSdk30
            }

            mIgnoredPaths.add(fileDirItem.path)
            val media = mMediaFiles.filter { !mIgnoredPaths.contains(it.path) } as ArrayList<Medium>
            if (media.isNotEmpty()) {
                runOnUiThread {
                    refreshUI(media, false)
                }
            }

            if (media.size == 1) {
                onPageSelected(0)
            }

            tryDeleteFileDirItem(fileDirItem, false, true) {
                mIgnoredPaths.remove(fileDirItem.path)
                if (media.isEmpty()) {
                    deleteDirectoryIfEmpty()
                    finish()
                }
            }
        }
    }

    private fun isDirEmpty(media: ArrayList<Medium>): Boolean {
        return if (media.isEmpty()) {
            deleteDirectoryIfEmpty()
            finish()
            true
        } else {
            false
        }
    }

    private fun checkMediaManagementAndRename() {
        handleMediaManagementPrompt {
            renameFile()
        }
    }

    private fun renameFile() {
        val oldPath = getCurrentPath()

        val isSDOrOtgRootFolder = isAStorageRootFolder(oldPath.getParentPath()) && !oldPath.startsWith(internalStoragePath)
        if (isRPlus() && isSDOrOtgRootFolder && !isExternalStorageManager()) {
            toast(com.simplemobiletools.commons.R.string.rename_in_sd_card_system_restriction, Toast.LENGTH_LONG)
            return
        }

        RenameItemDialog(this, oldPath) {
            getCurrentMedia().getOrNull(mPos)?.apply {
                path = it
                name = it.getFilenameFromPath()
            }

            ensureBackgroundThread {
                updateDBMediaPath(oldPath, it)
            }
            updateActionbarTitle()
        }
    }

    private fun refreshViewPager(refetchPosition: Boolean = false) {
        val isRandomSorting = config.getFolderSorting(mDirectory) and SORT_BY_RANDOM != 0
        if (!isRandomSorting || isExternalIntent()) {
            GetMediaAsynctask(applicationContext, mDirectory, isPickImage = false, isPickVideo = false, showAll = mShowAll) {
                gotMedia(it, refetchViewPagerPosition = refetchPosition)
            }.execute()
        }
    }

    private fun gotMedia(thumbnailItems: ArrayList<ThumbnailItem>, ignorePlayingVideos: Boolean = false, refetchViewPagerPosition: Boolean = false) {
        val media = thumbnailItems.asSequence().filter {
            it is Medium && !mIgnoredPaths.contains(it.path)
        }.map { it as Medium }.toMutableList() as ArrayList<Medium>

        if (isDirEmpty(media) || media.hashCode() == mPrevHashcode) {
            return
        }

        val isPlaying = (getCurrentFragment() as? VideoFragment)?.mIsPlaying == true
        if (!ignorePlayingVideos && isPlaying && !isExternalIntent()) {
            return
        }

        refreshUI(media, refetchViewPagerPosition)
    }

    private fun refreshUI(media: ArrayList<Medium>, refetchViewPagerPosition: Boolean) {
        mPrevHashcode = media.hashCode()
        mMediaFiles = media

        if (refetchViewPagerPosition || mPos == -1) {
            mPos = getPositionInList(media)
            if (mPos == -1) {
                min(mPos, media.lastIndex)
            }
        }

        updateActionbarTitle()
        updatePagerItems(mMediaFiles.toMutableList())

        refreshMenuItems()
        checkOrientation()
        initBottomActions()
    }

    private fun getPositionInList(items: MutableList<Medium>): Int {
        mPos = 0
        for ((i, medium) in items.withIndex()) {
            val portraitPath = getPortraitPath()
            if (portraitPath != "") {
                val portraitPaths = File(portraitPath).parentFile?.list()
                if (portraitPaths != null) {
                    for (path in portraitPaths) {
                        if (medium.name == path) {
                            return i
                        }
                    }
                }
            } else if (medium.path.equals(mPath, true)) {
                return i
            }
        }
        return mPos
    }

    private fun deleteDirectoryIfEmpty() {
        if (config.deleteEmptyFolders) {
            val fileDirItem = FileDirItem(mDirectory, mDirectory.getFilenameFromPath(), File(mDirectory).isDirectory)
            if (!fileDirItem.isDownloadsFolder() && fileDirItem.isDirectory) {
                ensureBackgroundThread {
                    if (fileDirItem.getProperFileCount(this, true) == 0) {
                        tryDeleteFileDirItem(fileDirItem, true, true)
                        scanPathRecursively(mDirectory)
                    }
                }
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun checkOrientation() {
        if (!mIsOrientationLocked && config.screenRotation == ROTATE_BY_ASPECT_RATIO) {
            var flipSides = false
            try {
                val pathToLoad = getCurrentPath()
                val exif = ExifInterface(pathToLoad)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
                flipSides = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
            } catch (e: Exception) {
            }
            val resolution = applicationContext.getResolution(getCurrentPath()) ?: return
            val width = if (flipSides) resolution.y else resolution.x
            val height = if (flipSides) resolution.x else resolution.y
            if (width > height) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else if (width < height) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    override fun fragmentClicked() {
        // one tap hides the toolbars but keeps the strip, the next one leaves just the photo
        mViewerUiState = when (mViewerUiState) {
            VIEWER_UI_ALL -> VIEWER_UI_NO_TOOLBARS
            VIEWER_UI_NO_TOOLBARS -> VIEWER_UI_IMAGE_ONLY
            else -> VIEWER_UI_ALL
        }
        mIsFullScreen = mViewerUiState != VIEWER_UI_ALL
        checkSystemUI()
        fullscreenToggled()
    }

    /**
     * A tap on the media itself. Which third (or which L corner) was tapped decides
     * whether we flip the page or just toggle the toolbars.
     */
    override fun fragmentClickedAt(x: Float, y: Float) {
        val action = when {
            // a single item has nothing to flip to, and "disabled" must still leave a way
            // to bring the toolbars back, so both of them only toggle the ui
            mMediaFiles.size <= 1 -> TAP_ACTION_MENU
            config.viewerTapZone == TAP_ZONE_DISABLED -> TAP_ACTION_MENU
            else -> TapZones.resolve(config.viewerTapZone, config.viewerTapZoneInvert, x, y, binding.fragmentHolder.width, binding.fragmentHolder.height)
        }

        when (action) {
            TAP_ACTION_PREVIOUS -> goToPrevItem()
            TAP_ACTION_NEXT -> goToNextItem()
            TAP_ACTION_MENU -> fragmentClicked()
            else -> {}
        }
    }

    override fun usesTapZones() = true

    override fun videoEnded(): Boolean {
        if (mIsSlideshowActive) {
            swipeToNextMedium()
        }
        return mIsSlideshowActive
    }

    override fun isSlideShowActive() = mIsSlideshowActive

    override fun goToPrevItem() {
        val target = binding.viewPager.currentItem - 1
        if (target < 0) {
            return
        }
        binding.viewPager.setCurrentItem(target, false)
        checkOrientation()
    }

    override fun goToNextItem() {
        val target = binding.viewPager.currentItem + 1
        if (target > mMediaFiles.lastIndex) {
            return
        }
        binding.viewPager.setCurrentItem(target, false)
        checkOrientation()
    }

    override fun launchViewVideoIntent(path: String) {
        hideKeyboard()
        ensureBackgroundThread {
            val newUri = getFinalUriFromPath(path, BuildConfig.APPLICATION_ID) ?: return@ensureBackgroundThread
            val mimeType = getUriMimeType(path, newUri)
            Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(newUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(IS_FROM_GALLERY, true)
                putExtra(REAL_FILE_PATH, path)
                putExtra(SHOW_PREV_ITEM, binding.viewPager.currentItem != 0)
                putExtra(SHOW_NEXT_ITEM, binding.viewPager.currentItem != mMediaFiles.lastIndex)

                try {
                    startActivityForResult(this, REQUEST_VIEW_VIDEO)
                } catch (e: ActivityNotFoundException) {
                    if (!tryGenericMimeType(this, mimeType, newUri)) {
                        toast(com.simplemobiletools.commons.R.string.no_app_found)
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        }
    }

    private fun checkSystemUI() {
        if (mIsFullScreen) {
            hideSystemUI(true)
        } else {
            stopSlideshow()
            showSystemUI(true)
        }
    }

    private fun fullscreenToggled() {
        binding.viewPager.adapter?.let {
            (it as MyPagerAdapter).toggleFullscreen(mViewerUiState != VIEWER_UI_ALL)

            val showToolbars = mViewerUiState == VIEWER_UI_ALL
            val showStrip = shouldShowStripInCurrentState()

            fadeLayer(binding.topShadow, showToolbars)
            fadeLayer(binding.mediumViewerAppbar, showToolbars)
            fadeLayer(binding.bottomActions.root, showToolbars && config.bottomActions)
            fadeLayer(binding.viewerThumbnailStripWrapper, showStrip)
            // the page number belongs to the strip layer, it goes away only with the last step
            fadeLayer(binding.viewerPageIndicator, shouldShowPageIndicator())

            if (showStrip) {
                // coming back from the image-only step the strip has no width yet, so re-center it
                binding.viewerThumbnailStrip.post { centerThumbnail(binding.viewPager.currentItem, false) }
            }
        }
    }

    /** Fades a layer in or out, and only drops it from the layout once the fade is over. */
    private fun fadeLayer(view: View, visible: Boolean) {
        view.animate().alpha(if (visible) 1f else 0f).withStartAction {
            view.beVisible()
        }.withEndAction {
            view.beVisibleIf(visible)
        }.start()
    }

    private fun shouldShowStripInCurrentState() = mViewerUiState != VIEWER_UI_IMAGE_ONLY && shouldShowThumbnailStrip()

    private fun shouldShowPageIndicator() = mViewerUiState != VIEWER_UI_IMAGE_ONLY && config.viewerPageIndicator && mMediaFiles.size > 1

    private fun updateActionbarTitle() {
        runOnUiThread {
            val medium = getCurrentMedium()
            if (medium != null) {
                binding.mediumViewerToolbar.title = medium.path.getFilenameFromPath()
            }
        }
    }

    private fun getCurrentMedium(): Medium? {
        return if (getCurrentMedia().isEmpty() || mPos == -1) {
            null
        } else {
            getCurrentMedia()[min(mPos, getCurrentMedia().lastIndex)]
        }
    }

    private fun getCurrentMedia() = if (mAreSlideShowMediaVisible || mRandomSlideshowStopped) mSlideshowMedia else mMediaFiles

    private fun getCurrentPath() = getCurrentMedium()?.path ?: ""

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        if (mPos != position) {
            mPos = position
            updateActionbarTitle()
            refreshMenuItems()
            scheduleSwipe()
            mThumbnailStripAdapter?.setCurrentItem(position)
            if (shouldShowStripInCurrentState() && !mIsUserScrollingStrip) {
                centerThumbnail(position)
            }
            updatePageIndicator()
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
        if (state == ViewPager.SCROLL_STATE_IDLE && getCurrentMedium() != null) {
            checkOrientation()
        }
    }

    private fun isExternalIntent(): Boolean {
        return !intent.getBooleanExtra(IS_FROM_GALLERY, false)
    }
}
