package com.simplemobiletools.gallery.pro.adapters

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.simplemobiletools.commons.extensions.getProperPrimaryColor
import com.simplemobiletools.commons.extensions.isPathOnOTG
import com.simplemobiletools.commons.extensions.getOTGPublicPath
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.databinding.ViewerThumbnailGridItemBinding
import com.simplemobiletools.gallery.pro.databinding.ViewerThumbnailStripItemBinding
import com.simplemobiletools.gallery.pro.helpers.THUMBNAIL_SHAPE_ORIGINAL
import com.simplemobiletools.gallery.pro.models.Medium

/**
 * Shared thumbnail loading for the bottom strip and the fullscreen grid.
 *
 * The thumbnails are decoded straight from the original file with an explicit
 * `override` size, so Glide downsamples while decoding instead of loading a 12
 * megapixel bitmap for a 48dp square.
 */
private fun loadThumbnail(context: Context, medium: Medium, imageView: ImageView, width: Int, height: Int, shape: Int) {
    val path = if (context.isPathOnOTG(medium.path)) medium.path.getOTGPublicPath(context) else medium.path
    val options = RequestOptions()
        .signature(medium.getKey())
        .override(width, height)
        .downsample(DownsampleStrategy.CENTER_OUTSIDE)
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .placeholder(ColorDrawable(0x26FFFFFF))

    if (shape == THUMBNAIL_SHAPE_ORIGINAL) {
        options.fitCenter()
    } else {
        options.centerCrop()
    }

    Glide.with(context)
        .load(path)
        .apply(options)
        .into(imageView)
}

private fun selectionBackground(context: Context, selected: Boolean, cornerRadius: Float, fillColor: Int): GradientDrawable {
    val strokeWidth = (context.resources.displayMetrics.density + 0.5f).toInt()
    val drawable = GradientDrawable()
    drawable.cornerRadius = cornerRadius
    drawable.setColor(fillColor)
    if (selected) {
        drawable.setStroke(strokeWidth * 2, context.getProperPrimaryColor())
    } else {
        drawable.setStroke(strokeWidth, 0x33FFFFFF)
    }
    return drawable
}

/**
 * Horizontal strip of thumbnails above the bottom actions in the fullscreen viewer.
 */
class ViewerThumbnailStripAdapter(val context: Context, val media: List<Medium>, val itemClick: (Int) -> Unit) :
    RecyclerView.Adapter<ViewerThumbnailStripAdapter.ViewHolder>() {

    var currentSelectionIndex = -1
    var thumbnailShape = THUMBNAIL_SHAPE_ORIGINAL

    // set by the owner, so the selection can be repainted in place instead of re-binding items
    var recyclerView: RecyclerView? = null

    // 3:4 portrait cells, the same proportions the pixez strip uses
    val itemWidth = context.resources.getDimension(R.dimen.viewer_thumbnail_strip_item_width).toInt()
    val itemHeight = context.resources.getDimension(R.dimen.viewer_thumbnail_strip_item_height).toInt()
    private val cornerRadius = context.resources.getDimension(R.dimen.viewer_thumbnail_corner_radius)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ViewerThumbnailStripItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val gap = context.resources.getDimension(R.dimen.viewer_thumbnail_strip_gap).toInt()
        binding.root.layoutParams = RecyclerView.LayoutParams(itemWidth, itemHeight).apply {
            marginStart = gap / 2
            marginEnd = gap / 2
        }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bindView(media[position], position)

    override fun getItemCount() = media.size

    /**
     * Repaints the two affected cells instead of calling notifyItemChanged on them.
     *
     * A re-bind means a new drawable plus a new Glide request for every single page the finger
     * passes, which is exactly what made the strip stutter while scrolling fast. Only the
     * border changes here, so touching the visible holders directly is enough.
     */
    fun setCurrentItem(position: Int) {
        if (currentSelectionIndex == position) {
            return
        }
        val previous = currentSelectionIndex
        currentSelectionIndex = position

        val grid = recyclerView
        if (grid == null) {
            if (previous in media.indices) {
                notifyItemChanged(previous)
            }
            if (position in media.indices) {
                notifyItemChanged(position)
            }
            return
        }

        for (i in 0 until grid.childCount) {
            val holder = grid.getChildViewHolder(grid.getChildAt(i)) as? ViewHolder ?: continue
            val holderPosition = holder.bindingAdapterPosition
            if (holderPosition != previous && holderPosition != position) {
                continue
            }
            holder.applySelection(holderPosition == position)
        }
    }

    /** Called when the user picks the other thumbnail shape in the grid. */
    fun updateShape(shape: Int) {
        if (thumbnailShape == shape) {
            return
        }
        thumbnailShape = shape
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ViewerThumbnailStripItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindView(medium: Medium, position: Int) {
            applySelection(position == currentSelectionIndex)
            binding.root.setOnClickListener { itemClick(bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position) }
            loadThumbnail(context, medium, binding.thumbnailStripImage, itemWidth, itemHeight, thumbnailShape)
        }

        fun applySelection(selected: Boolean) {
            binding.root.background = selectionBackground(context, selected, cornerRadius, if (selected) 0x33FFFFFF else 0x00000000)
        }
    }
}

/**
 * Thumbnails grid shown as a bottom sheet, the "fly out" version of the strip.
 */
class ViewerThumbnailGridAdapter(
    val context: Context,
    val media: List<Medium>,
    val itemSize: Int,
    val itemHeight: Int,
    val itemClick: (Int) -> Unit
) : RecyclerView.Adapter<ViewerThumbnailGridAdapter.ViewHolder>() {

    var currentSelectionIndex = -1
    var thumbnailShape = THUMBNAIL_SHAPE_ORIGINAL
    private val cornerRadius = context.resources.getDimension(R.dimen.viewer_thumbnail_corner_radius)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ViewerThumbnailGridItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bindView(media[position], position)

    override fun getItemCount() = media.size

    fun setCurrentItem(position: Int) {
        if (currentSelectionIndex == position) {
            return
        }
        val previous = currentSelectionIndex
        currentSelectionIndex = position
        if (previous in media.indices) {
            notifyItemChanged(previous)
        }
        if (position in media.indices) {
            notifyItemChanged(position)
        }
    }

    fun updateShape(shape: Int) {
        if (thumbnailShape == shape) {
            return
        }
        thumbnailShape = shape
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ViewerThumbnailGridItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindView(medium: Medium, position: Int) {
            val selected = position == currentSelectionIndex
            binding.thumbnailGridImage.background = selectionBackground(context, selected, cornerRadius, 0x1AFFFFFF)
            binding.root.setOnClickListener { itemClick(position) }

            // the page number is part of the cell only when there is more than one item
            binding.thumbnailGridNumber.visibility = if (media.size > 1) View.VISIBLE else View.GONE
            binding.thumbnailGridNumber.text = (position + 1).toString()
            binding.thumbnailGridNumber.setTextColor(if (selected) context.getProperPrimaryColor() else 0xB3FFFFFF.toInt())
            binding.thumbnailGridNumber.paint.isFakeBoldText = selected

            loadThumbnail(context, medium, binding.thumbnailGridImage, itemSize, itemSize, thumbnailShape)
        }
    }
}
