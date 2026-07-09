package com.smartmediapicker

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayList

class SmartMediaPickerLimitedAssetPickerActivity : Activity() {

    private lateinit var gridView: GridView
    private lateinit var adapter: AssetAdapter
    private var mediaType: String = "photo"
    private var selectionLimit: Int = 1
    private val assetsList = ArrayList<AssetItem>()
    private val selectedItems = ArrayList<AssetItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Options
        mediaType = intent.getStringExtra("mediaType") ?: "photo"
        selectionLimit = intent.getIntExtra("selectionLimit", 1)

        // Setup layout programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Custom Title/Header Bar
        val headerLayout = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                140
            )
            setBackgroundColor(Color.parseColor("#1C1C1E"))
        }

        val titleView = TextView(this).apply {
            text = "Select Allowed Media"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        headerLayout.addView(titleView)

        rootLayout.addView(headerLayout)

        // Grid View for thumbnails
        gridView = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = 4
            verticalSpacing = 4
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            ).apply {
                setMargins(4, 4, 4, 4)
            }
        }
        rootLayout.addView(gridView)

        // Bottom Button bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 16, 16, 32)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        val btnDone = Button(this).apply {
            text = "Done"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#007AFF"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(8, 8, 8, 8)
            }
            setOnClickListener {
                if (selectedItems.isEmpty()) {
                    setResult(RESULT_CANCELED)
                    finish()
                } else {
                    val uris = ArrayList<String>()
                    for (item in selectedItems) {
                        uris.add(item.uri.toString())
                    }
                    val data = android.content.Intent().apply {
                        putStringArrayListExtra("selectedUris", uris)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                }
            }
        }

        bottomBar.addView(btnCancel)
        bottomBar.addView(btnDone)
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)

        adapter = AssetAdapter(this, assetsList, selectedItems)
        gridView.adapter = adapter

        // Fetch limited media
        loadLimitedAssets()
    }

    private fun loadLimitedAssets() {
        assetsList.clear()
        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.SIZE
            )

            // Select only image/video types
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )

            val queryUri = MediaStore.Files.getContentUri("external")
            val cursor = contentResolver.query(
                queryUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val type = it.getInt(typeColumn)
                    val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    
                    val uri = ContentUris.withAppendedId(
                        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    // Filter list by mediaType option
                    if (mediaType == "photo" && isVideo) continue
                    if (mediaType == "video" && !isVideo) continue

                    assetsList.add(AssetItem(uri, isVideo))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load limited assets", Toast.LENGTH_SHORT).show()
        }
        adapter.notifyDataSetChanged()
    }

    private class AssetItem(val uri: Uri, val isVideo: Boolean)

    private inner class AssetAdapter(
        private val context: Context,
        private val list: List<AssetItem>,
        private val selected: ArrayList<AssetItem>
    ) : BaseAdapter() {

        override fun getCount(): Int = list.size
        override fun getItem(position: Int): Any = list[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = list[position]
            val container: FrameLayout
            val imageView: ImageView
            val overlay: View
            val videoIcon: ImageView

            if (convertView == null) {
                container = FrameLayout(context).apply {
                    layoutParams = android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        320
                    )
                }

                imageView = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                container.addView(imageView)

                // Selection checkmark indicator overlay
                overlay = View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        50,
                        50
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, 12, 12, 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(4, Color.WHITE)
                    }
                }
                container.addView(overlay)

                // Video Duration / indicator icon
                videoIcon = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        40,
                        40
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.START
                        setMargins(12, 0, 0, 12)
                    }
                    setImageResource(android.R.drawable.ic_media_play)
                    setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    visibility = View.GONE
                }
                container.addView(videoIcon)

                container.tag = ViewHolder(imageView, overlay, videoIcon)
            } else {
                container = convertView as FrameLayout
                val holder = container.tag as ViewHolder
                imageView = holder.imageView
                overlay = holder.overlay
                videoIcon = holder.videoIcon
            }

            // Load Thumbnail safely
            imageView.setImageBitmap(null)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val thumb = context.contentResolver.loadThumbnail(item.uri, Size(200, 200), null)
                    imageView.setImageBitmap(thumb)
                }
            } catch (e: Exception) {
                // Fallback decode if loadThumbnail fails
                e.printStackTrace()
            }

            videoIcon.visibility = if (item.isVideo) View.VISIBLE else View.GONE

            // Selection state styling
            val isSelected = selected.contains(item)
            if (isSelected) {
                overlay.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#007AFF"))
                    setStroke(4, Color.WHITE)
                }
            } else {
                overlay.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(4, Color.WHITE)
                }
            }

            container.setOnClickListener {
                if (selected.contains(item)) {
                    selected.remove(item)
                } else {
                    if (selectionLimit > 0 && selected.size >= selectionLimit) {
                        if (selectionLimit == 1) {
                            selected.clear()
                            selected.add(item)
                        } else {
                            Toast.makeText(context, "Limit reached: max $selectionLimit items", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                    } else {
                        selected.add(item)
                    }
                }
                notifyDataSetChanged()
            }

            return container
        }

        private inner class ViewHolder(
            val imageView: ImageView,
            val overlay: View,
            val videoIcon: ImageView
        )
    }
}
