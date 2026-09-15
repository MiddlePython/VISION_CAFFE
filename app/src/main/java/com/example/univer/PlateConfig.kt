package com.example.univer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class SimplePlatesAdapter(
    private var plates: List<PlateEntity>,
    private val onPlateSelected: (PlateEntity) -> Unit
) : RecyclerView.Adapter<SimplePlatesAdapter.PlateViewHolder>() {

    private var selectedIndex = 0

    class PlateViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.cardPlate)
        val tvName: TextView = v.findViewById(R.id.tvPlateName)
        val tvWeight: TextView = v.findViewById(R.id.tvPlateWeight)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlateViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_plate_card, parent, false)
        return PlateViewHolder(v)
    }

    override fun onBindViewHolder(holder: PlateViewHolder, position: Int) {
        val plate = plates[position]
        holder.tvName.text = plate.name
        holder.tvWeight.text = "${plate.weightGrams} г"

        val isSelected = position == selectedIndex
        holder.card.strokeColor = if (isSelected) android.graphics.Color.parseColor("#0088CC") else android.graphics.Color.parseColor("#E5E7EB")
        holder.card.strokeWidth = if (isSelected) 4 else 1
        holder.card.setCardBackgroundColor(if (isSelected) android.graphics.Color.parseColor("#EDF7FD") else android.graphics.Color.WHITE)

        holder.itemView.setOnClickListener {
            val oldIndex = selectedIndex
            selectedIndex = holder.adapterPosition
            notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
            onPlateSelected(plate)
        }
    }

    override fun getItemCount() = plates.size

    fun updateData(newPlates: List<PlateEntity>) {
        this.plates = newPlates
        notifyDataSetChanged()
    }
}
