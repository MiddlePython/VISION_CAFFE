package com.example.univer

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Entity(tableName = "dishes")
data class DishEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val pricePerGram: Double
)

@Dao
interface DishDao {
    // ИСПРАВЛЕНО: Добавлен suspend, чтобы Room разрешал безопасный вызов в корутинах
    @Query("SELECT * FROM dishes ORDER BY name ASC")
    suspend fun getAllDishes(): List<DishEntity>

    // ИСПРАВЛЕНО: Добавлен suspend для безопасного получения списка категорий
    @Query("SELECT DISTINCT category FROM dishes ORDER BY category ASC")
    suspend fun getAllCategories(): List<String>

    @Query("SELECT * FROM dishes WHERE category = :category ORDER BY name ASC")
    suspend fun getDishesByCategory(category: String): List<DishEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDish(dish: DishEntity)

    @Delete
    suspend fun deleteDish(dish: DishEntity)
}

@Database(entities = [DishEntity::class], version = 1, exportSchema = false)
abstract class DishDatabase : RoomDatabase() {
    abstract fun dishDao(): DishDao

    companion object {
        @Volatile
        private var INSTANCE: DishDatabase? = null

        fun getDatabase(context: Context): DishDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DishDatabase::class.java,
                    "dish_database"
                )
                    .addCallback(DatabaseCallback(context))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val database = INSTANCE ?: return@launch
                    val dao = database.dishDao()

                    // ИСПРАВЛЕНО: Теперь этот вызов безопасен, так как метод стал suspend
                    val existingDishes = dao.getAllDishes()
                    if (existingDishes.isEmpty()) {
                        val defaultDishes = listOf(
                            DishEntity(name = "Борщ с говядиной", category = "Супы", pricePerGram = 0.45),
                            DishEntity(name = "Суп куриный с лапшой", category = "Супы", pricePerGram = 0.35),
                            DishEntity(name = "Котлета домашняя", category = "Горячее", pricePerGram = 0.85),
                            DishEntity(name = "Плов с курицей", category = "Горячее", pricePerGram = 0.60),
                            DishEntity(name = "Пюре картофельное", category = "Гарниры", pricePerGram = 0.20),
                            DishEntity(name = "Рис с овощами", category = "Гарниры", pricePerGram = 0.25),
                            DishEntity(name = "Оливье", category = "Салаты", pricePerGram = 0.50),
                            DishEntity(name = "Цезарь", category = "Салаты", pricePerGram = 0.75)
                        )
                        defaultDishes.forEach { dao.insertDish(it) }
                    }
                }
            }
        }
    }
}
