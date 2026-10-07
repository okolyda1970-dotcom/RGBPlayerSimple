package com.example.rgbplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.rgbplayer.R
import com.example.rgbplayer.Track

class TrackAdapter(
    private val tracks: List<Track>,
    private val onItemClick: (Track) -> Unit,
    private var currentTrackIndex: Int = -1
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.tvTitle.text = track.title
        holder.tvArtist.text = track.artist

        if (position == currentTrackIndex) {
            holder.itemView.setBackgroundResource(R.drawable.item_track_active_background)
            holder.tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.white))
        } else {
            holder.itemView.setBackgroundResource(R.drawable.item_track_background)
            holder.tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.white))
        }

        holder.itemView.setOnClickListener {
            onItemClick(track)
        }
    }

    override fun getItemCount(): Int = tracks.size

    // ВАЖНО: этот метод должен быть здесь!
    fun setCurrentTrackIndex(index: Int) {
        val oldIndex = currentTrackIndex
        currentTrackIndex = index

        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (index >= 0) notifyItemChanged(index)
    }
}