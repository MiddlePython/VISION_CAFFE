package com.example.univer // Замените на имя вашего пакета, если оно отличается

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

data class PlateItem(
    val id: String,
    val name: String,
    val weightGrams: Int,
    val imageUrl: String? = null
)

class PlatesAdapter(
    private val plates: List<PlateItem>,
    private val onPlateSelected: (PlateItem) -> Unit
) : RecyclerView.Adapter<PlatesAdapter.PlateViewHolder>() {

    private var selectedPosition = 0

    inner class PlateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardPlate)
        val tvName: TextView = itemView.findViewById(R.id.tvPlateName)
        val tvWeight: TextView = itemView.findViewById(R.id.tvPlateWeight)
        val img: ImageView = itemView.findViewById(R.id.imgPlate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlateViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plate_card, parent, false)
        return PlateViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlateViewHolder, position: Int) {
        val plate = plates[position]
        holder.tvName.text = plate.name
        holder.tvWeight.text = "${plate.weightGrams} г"

        // Подсветка выбранной тарелки
        val isSelected = position == selectedPosition
        holder.card.strokeColor = if (isSelected) Color.parseColor("#0088CC") else Color.parseColor("#E0E0E0")
        holder.card.strokeWidth = if (isSelected) 4 else 1

        holder.itemView.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                val previous = selectedPosition
                selectedPosition = currentPos
                notifyItemChanged(previous)
                notifyItemChanged(selectedPosition)
                onPlateSelected(plate)
            }
        }
    }

    override fun getItemCount() = plates.size
}