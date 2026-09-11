package com.example.univer

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var lastOcrTime = 0L

    // Состояние весов
    private var grossWeightGrams: Int = 0
    private var tareWeightGrams: Int = 220
    private var selectedDish: DishEntity? = null

    // Компоненты Базы Данных и UI
    private lateinit var database: DishDatabase
    private lateinit var menuAdapter: MenuGridAdapter
    private var allDishesList: List<DishEntity> = emptyList()
    private var currentCategory: String = "Все"

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Фиксируем приложение POS-терминала в альбомной ориентации программно
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)

        database = DishDatabase.getDatabase(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupPlatesAdapter()
        setupMenuGrid()
        setupSearchAndFilter()
        setupActionButtons()

        val previewView = findViewById<PreviewView>(R.id.previewView)
        if (allPermissionsGranted()) {
            startCamera(previewView)
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Загружаем данные из БД
        loadDataFromDatabase()
    }

    // 1. СТРОГИЙ И БЕЗОПАСНЫЙ OCR ПАРСИНГ ФОРМАТА ВЕСОВ (0.XXX кг)
    @OptIn(ExperimentalGetImage::class)
    private fun processImageForOcr(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastOcrTime < 700) {
            imageProxy.close()
            return
        }
        lastOcrTime = currentTime

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val regex = Regex("\\d+[.,]\\d{3}")
                    var detectedWeight = 0

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val match = regex.find(line.text)?.value
                            if (match != null) {
                                val normalized = match.replace(',', '.')
                                val parsedKg = normalized.toDoubleOrNull()
                                if (parsedKg != null && parsedKg > 0.0) {
                                    detectedWeight = (parsedKg * 1000).toInt()
                                    break
                                }
                            }
                        }
                        if (detectedWeight > 0) break
                    }

                    if (detectedWeight > 0 && detectedWeight != grossWeightGrams) {
                        grossWeightGrams = detectedWeight
                        runOnUiThread { updateCalculations() }
                    }
                    imageProxy.close()
                }
                .addOnFailureListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    // 2. РАБОТА С ROOM DATABASE
    private fun loadDataFromDatabase() {
        activityScope.launch {
            val dishes = withContext(Dispatchers.IO) { database.dishDao().getAllDishes() }
            val categories = withContext(Dispatchers.IO) { database.dishDao().getAllCategories() }

            allDishesList = dishes

            buildCategoryChips(categories)
            filterAndPopulateGrid()
        }
    }

    private fun buildCategoryChips(categories: List<String>) {
        val chipGroup = findViewById<ChipGroup>(R.id.chipGroupCategories)
        chipGroup.removeAllViews()

        val allChip = Chip(this).apply {
            text = "Все меню"
            isCheckable = true
            isChecked = true
            id = View.generateViewId()
            setOnClickListener {
                currentCategory = "Все"
                filterAndPopulateGrid()
            }
        }
        chipGroup.addView(allChip)

        for (category in categories) {
            val chip = Chip(this).apply {
                text = category
                isCheckable = true
                id = View.generateViewId()
                setOnClickListener {
                    currentCategory = category
                    filterAndPopulateGrid()
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun filterAndPopulateGrid() {
        val query = findViewById<EditText>(R.id.etSearchDish).text.toString().trim()

        val filteredList = allDishesList.filter { dish ->
            val matchesCategory = (currentCategory == "Все" || dish.category == currentCategory)
            val matchesSearch = dish.name.contains(query, ignoreCase = true)
            matchesCategory && matchesSearch
        }
        menuAdapter.updateData(filteredList)
    }

    private fun setupMenuGrid() {
        val rvGrid = findViewById<RecyclerView>(R.id.rvMenuGrid)
        rvGrid.layoutManager = GridLayoutManager(this, 3)

        menuAdapter = MenuGridAdapter(emptyList()) { dish ->
            selectedDish = dish
            findViewById<TextView>(R.id.tvDishName).text = dish.name
            updateCalculations()
        }
        rvGrid.adapter = menuAdapter
    }

    private fun setupSearchAndFilter() {
        findViewById<EditText>(R.id.etSearchDish).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndPopulateGrid()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // 3. ОКНО ДОБАВЛЕНИЯ НОВЫХ БЛЮД
    private fun showAddDishDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Добавить блюдо в систему")

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_dish, null)
        val etName = view.findViewById<EditText>(R.id.etNewDishName)
        val etCategory = view.findViewById<EditText>(R.id.etNewDishCategory)
        val etPrice100g = view.findViewById<EditText>(R.id.etNewDishPrice100g)

        builder.setView(view)
        builder.setPositiveButton("Сохранить") { dialog, _ ->
            val name = etName.text.toString().trim()
            val category = etCategory.text.toString().trim()
            val price100g = etPrice100g.text.toString().toDoubleOrNull() ?: 0.0

            if (name.isNotEmpty() && category.isNotEmpty() && price100g > 0.0) {
                val pricePerGram = price100g / 100.0
                val newDish = DishEntity(name = name, category = category, pricePerGram = pricePerGram)

                activityScope.launch {
                    withContext(Dispatchers.IO) {
                        database.dishDao().insertDish(newDish)
                    }
                    Toast.makeText(this@MainActivity, "Блюдо добавлено!", Toast.LENGTH_SHORT).show()
                    loadDataFromDatabase()
                }
            } else {
                Toast.makeText(this, "Заполните все поля корректно!", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Отмена") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    // 4. МАТЕМАТИКА И АНИМАЦИЯ ЦЕНЫ
    private fun updateCalculations() {
        val netWeight = (grossWeightGrams - tareWeightGrams).coerceAtLeast(0)
        val priceFactor = selectedDish?.pricePerGram ?: 0.0
        val totalPrice = netWeight * priceFactor

        val tvWeightInfo = findViewById<TextView>(R.id.tvWeightInfo)
        val tvTotalPrice = findViewById<TextView>(R.id.tvTotalPrice)

        tvWeightInfo.text = "Вес нетто: $netWeight г (Тара: $tareWeightGrams г | Брутто: $grossWeightGrams г)"

        val currentPriceText = tvTotalPrice.text.toString().replace("[^0-9.]".toRegex(), "")
        val oldPrice = currentPriceText.toDoubleOrNull() ?: 0.0

        val animator = ValueAnimator.ofFloat(oldPrice.toFloat(), totalPrice.toFloat())
        animator.duration = 250
        animator.addUpdateListener { anim ->
            val value = anim.animatedValue as Float
            tvTotalPrice.text = String.format("%.2f ₽", value)
        }
        animator.start()
    }

    private fun setupPlatesAdapter() {
        val plates = listOf(
            TarePlate("1", "Глубокая", 220),
            TarePlate("2", "Плоская", 180),
            TarePlate("3", "Салатник", 150),
            TarePlate("4", "Контейнер", 45)
        )
        val adapter = SimplePlatesAdapter(plates) { selectedPlate ->
            tareWeightGrams = selectedPlate.weightGrams
            updateCalculations()
        }
        findViewById<RecyclerView>(R.id.rvPlates).adapter = adapter
    }

    private fun setupActionButtons() {
        findViewById<View>(R.id.btnPay).setOnClickListener {
            if (selectedDish == null) {
                Toast.makeText(this, "Сначала выберите блюдо!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "🚀 Чек успешно отправлен на терминал Т-Банка!", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            grossWeightGrams = 0
            updateCalculations()
            Toast.makeText(this, "Вес сброшен", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnAddNewDish).setOnClickListener {
            showAddDishDialog()
        }
    }

    private fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageForOcr(imageProxy)
                    }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}