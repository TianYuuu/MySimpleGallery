package com.simplemobiletools.gallery.pro.dialogs

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.activities.SimpleActivity
import com.simplemobiletools.gallery.pro.adapters.ViewerThumbnailGridAdapter
import com.simplemobiletools.gallery.pro.databinding.DialogViewerThumbnailsBinding
import com.simplemobiletools.gallery.pro.extensions.config
import com.simplemobiletools.gallery.pro.helpers.THUMBNAIL_SHAPE_ORIGINAL
import com.simplemobiletools.gallery.pro.helpers.THUMBNAIL_SHAPE_UNIFORM
import com.simplemobiletools.gallery.pro.models.Medium
import kotlin.math.roundToInt

/**
 * Fullscreen thumbnails grid, opened from the bottom strip. Picking a cell jumps
 * the viewer to that item, the seek bar does the same without closing the sheet.
 */
class ViewerThumbnailsDialog(
    val activity: SimpleActivity,
    val media: List<Medium>,
    currentIndex: Int,
    val onSelection: (Int) -> Unit
) : BottomSheetDialog(activity) {

    private val columns = 4
    private val binding = DialogViewerThumbnailsBinding.inflate(layoutInflater)
    private lateinit var gridAdapter: ViewerThumbnailGridAdapter
    private var selectedIndex = currentIndex
    private var isSyncingSeekBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screenWidth = activity.resources.displayMetrics.widthPixels
        setContentView(binding.root)
        window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        window?.setDimAmount(0.6f)

        setupGrid(screenWidth)
        setupSeekBar()
        updateCountText()
        scrollToSelected()
    }

    override fun onStart() {
        super.onStart()
        // the sheet is laid out with wrap_content on its own, so its height has to be given
        // explicitly, otherwise the grid collapses to a single row
        val screenHeight = activity.resources.displayMetrics.heightPixels
        val sheetHeight = (screenHeight * 0.92f).roundToInt()
        val sheet = window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.layoutParams?.let {
            it.height = sheetHeight
            sheet.layoutParams = it
        }
        behavior.apply {
            peekHeight = sheetHeight
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun setupGrid(screenWidth: Int) {
        val padding = context.resources.getDimension(R.dimen.viewer_thumbnail_grid_padding).toInt()
        val spacing = context.resources.getDimension(R.dimen.viewer_thumbnail_grid_spacing).toInt()
        val usableWidth = screenWidth - padding * 2 - spacing * (columns - 1)
        val cellWidth = usableWidth / columns
        val cellHeight = cellWidth + context.resources.getDimension(R.dimen.viewer_thumbnail_grid_label_height).toInt()

        val shape = activity.config.viewerThumbnailShape
        gridAdapter = ViewerThumbnailGridAdapter(activity, media, cellWidth, cellHeight) { position ->
            select(position)
            onSelection(position)
            dismiss()
        }

        gridAdapter.currentSelectionIndex = selectedIndex
        gridAdapter.thumbnailShape = shape

        binding.thumbnailGrid.apply {
            layoutManager = GridLayoutManager(activity, columns)
            addItemDecoration(GridSpacingDecoration(spacing))
            adapter = gridAdapter
        }

        binding.thumbnailGridShape.setImageResource(R.drawable.ic_aspect_ratio_vector)
        binding.thumbnailGridShape.setOnClickListener {
            val newShape = if (activity.config.viewerThumbnailShape == THUMBNAIL_SHAPE_UNIFORM) {
                THUMBNAIL_SHAPE_ORIGINAL
            } else {
                THUMBNAIL_SHAPE_UNIFORM
            }
            activity.config.viewerThumbnailShape = newShape
            gridAdapter.updateShape(newShape)
        }

        binding.thumbnailGridClose.setOnClickListener { dismiss() }
    }

    private fun setupSeekBar() {
        val seekBar = binding.thumbnailGridSeekbar
        if (media.size <= 1) {
            seekBar.visibility = View.GONE
            return
        }

        seekBar.max = media.size - 1
        seekBar.progress = selectedIndex
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isSyncingSeekBar) {
                    return
                }
                select(progress)
                onSelection(progress)
                scrollToSelected()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun select(position: Int) {
        if (position !in media.indices) {
            return
        }
        selectedIndex = position
        gridAdapter.setCurrentItem(position)
        isSyncingSeekBar = true
        binding.thumbnailGridSeekbar.progress = position
        isSyncingSeekBar = false
        updateCountText()
    }

    private fun updateCountText() {
        binding.thumbnailGridCount.text = "${selectedIndex + 1}/${media.size}"
    }

    private fun scrollToSelected() {
        val layoutManager = binding.thumbnailGrid.layoutManager as? GridLayoutManager ?: return
        val spacing = context.resources.getDimension(R.dimen.viewer_thumbnail_grid_spacing).toInt()
        val rowHeight = gridAdapter.itemHeight + spacing
        val row = selectedIndex / columns
        // start half a row lower so the selected cell never sticks to the top edge
        binding.thumbnailGrid.post {
            layoutManager.scrollToPositionWithOffset(row * columns, rowHeight / 2)
        }
    }

    /** Even spacing around the cells, the default grid only has gaps between items. */
    private class GridSpacingDecoration(val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.left = spacing / 2
            outRect.right = spacing / 2
            outRect.top = spacing / 2
            outRect.bottom = spacing / 2
        }
    }
}
