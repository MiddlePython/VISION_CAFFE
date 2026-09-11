package com.example.univer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class MenuGridAdapter(
    private var items: List<DishEntity>,
    private val onDishClick: (DishEntity) -> Unit
) : RecyclerView.Adapter<MenuGridAdapter.TileViewHolder>() {

    private var selectedId: Long = -1

    class TileViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.cardDishTile)
        val tvName: TextView = v.findViewById(R.id.tvTileDishName)
        val tvPrice: TextView = v.findViewById(R.id.tvTileDishPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_dish_tile, parent, false)
        return TileViewHolder(v)
    }

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name

        // Превращаем цену за 1г в красивую цену за 100г для кассира
        val pricePer100g = (item.pricePerGram * 100)
        holder.tvPrice.text = String.format("%.2f ₽/100г", pricePer100g)

        // Индикация выбранного блюда
        val isSelected = item.id == selectedId
        holder.card.strokeColor = if (isSelected) android.graphics.Color.parseColor("#0088CC") else android.graphics.Color.parseColor("#E5E7EB")
        holder.card.strokeWidth = if (isSelected) 4 else 1
        holder.card.setCardBackgroundColor(if (isSelected) android.graphics.Color.parseColor("#EDF7FD") else android.graphics.Color.WHITE)

        holder.itemView.setOnClickListener {
            val prevSelected = selectedId
            selectedId = item.id

            // Локально обновляем старый и новый элементы, чтобы не перерисовывать всю сетку через notifyDataSetChanged
            val prevIndex = items.indexOfFirst { it.id == prevSelected }
            if (prevIndex != -1) notifyItemChanged(prevIndex)
            notifyItemChanged(holder.adapterPosition)

            onDishClick(item)
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<DishEntity>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
