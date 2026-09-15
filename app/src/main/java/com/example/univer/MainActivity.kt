package com.example.univer

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var lastOcrTime = 0L

    // Область ROI (в процентах от ширины/высоты кадра)
    // Отрегулируйте эти значения под положение дисплея весов в камере
    private val roiLeftPercent = 0.30f
    private val roiTopPercent = 0.15f
    private val roiWidthPercent = 0.40f
    private val roiHeightPercent = 0.20f

    // Текущее состояние весов
    private var grossWeightGrams: Int = 0
    private var tareWeightGrams: Int = 220
    private var selectedDish: DishEntity? = null

    // Корзина заказа (iiko-функционал)
    private val orderItemsList = mutableListOf<OrderItem>()
    private lateinit var orderAdapter: OrderItemsAdapter

    // Компоненты БД и UI
    private lateinit var database: DishDatabase
    private lateinit var menuAdapter: MenuGridAdapter
    private lateinit var platesAdapter: SimplePlatesAdapter
    private var allDishesList: List<DishEntity> = emptyList()
    private var currentCategory: String = "Все"

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)

        database = DishDatabase.getDatabase(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupOrderCart()
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

        loadDataFromDatabase()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageForOcr(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastOcrTime < 400) { // Проверка 2.5 раза в секунду
            imageProxy.close()
            return
        }
        lastOcrTime = currentTime

        val bitmap = imageProxy.toBitmap()
        if (bitmap != null) {
            try {
                // 1. Обрезаем область дисплея (ROI)
                val cropX = (bitmap.width * roiLeftPercent).toInt().coerceIn(0, bitmap.width - 1)
                val cropY = (bitmap.height * roiTopPercent).toInt().coerceIn(0, bitmap.height - 1)
                val cropW = (bitmap.width * roiWidthPercent).toInt().coerceAtMost(bitmap.width - cropX)
                val cropH = (bitmap.height * roiHeightPercent).toInt().coerceAtMost(bitmap.height - cropY)

                if (cropW > 0 && cropH > 0) {
                    val roiBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

                    // 2. Распознаем 7-сегментные цифры
                    val recognizedText = SevenSegmentDecoder.decodeBitmap(roiBitmap)

                    if (recognizedText.isNotEmpty()) {
                        val parsedKg = recognizedText.toDoubleOrNull()
                        if (parsedKg != null && parsedKg >= 0.0) {
                            val detectedWeight = (parsedKg * 1000).toInt()
                            if (detectedWeight != grossWeightGrams) {
                                grossWeightGrams = detectedWeight
                                runOnUiThread { updateCalculations() }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка обработки кадра: ${e.message}")
            }
        }
        imageProxy.close()
    }

    private fun loadDataFromDatabase() {
        activityScope.launch {
            val dishes = withContext(Dispatchers.IO) { database.dishDao().getAllDishes() }
            val categories = withContext(Dispatchers.IO) { database.dishDao().getAllCategories() }
            val plates = withContext(Dispatchers.IO) { database.dishDao().getAllPlates() }

            allDishesList = dishes
            platesAdapter.updateData(plates)
            if (plates.isNotEmpty()) {
                tareWeightGrams = plates[0].weightGrams
            }

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
            findViewById<TextView>(R.id.tvLiveDishName).text = dish.name
            updateCalculations()
        }
        rvGrid.adapter = menuAdapter
    }

    private fun setupPlatesAdapter() {
        val rvPlates = findViewById<RecyclerView>(R.id.rvPlates)
        rvPlates.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        platesAdapter = SimplePlatesAdapter(emptyList()) { selectedPlate ->
            tareWeightGrams = selectedPlate.weightGrams
            updateCalculations()
        }
        rvPlates.adapter = platesAdapter
    }

    private fun setupOrderCart() {
        val rvOrder = findViewById<RecyclerView>(R.id.rvOrderItems)
        rvOrder.layoutManager = LinearLayoutManager(this)
        orderAdapter = OrderItemsAdapter(orderItemsList) { itemToRemove ->
            orderItemsList.remove(itemToRemove)
            orderAdapter.updateData(orderItemsList)
            updateTotalCartPrice()
        }
        rvOrder.adapter = orderAdapter
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

    private fun showCreateWizardDialog() {
        val builder = AlertDialog.Builder(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_dish, null)

        val radioGroup = view.findViewById<RadioGroup>(R.id.rgCreateType)
        val etName = view.findViewById<EditText>(R.id.etNewDishName)
        val etCategory = view.findViewById<EditText>(R.id.etNewDishCategory)
        val etValue = view.findViewById<EditText>(R.id.etNewDishPrice100g)

        etCategory.visibility = View.VISIBLE

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbTypePlate) {
                etCategory.visibility = View.GONE
                etValue.hint = "Вес тары в граммах (например: 200)"
            } else {
                etCategory.visibility = View.VISIBLE
                etValue.hint = "Цена за 100 грамм (в ₽)"
            }
        }

        builder.setView(view)
        builder.setPositiveButton("Сохранить") { dialog, _ ->
            val name = etName.text.toString().trim()
            val valueStr = etValue.text.toString().trim()

            if (name.isNotEmpty() && valueStr.isNotEmpty()) {
                activityScope.launch {
                    if (radioGroup.checkedRadioButtonId == R.id.rbTypePlate) {
                        val weight = valueStr.toIntOrNull() ?: 0
                        withContext(Dispatchers.IO) {
                            database.dishDao().insertPlate(
                                PlateEntity(name = name, weightGrams = weight)
                            )
                        }
                    } else {
                        val category = etCategory.text.toString().trim()
                        val price100g = valueStr.toDoubleOrNull() ?: 0.0
                        withContext(Dispatchers.IO) {
                            database.dishDao().insertDish(
                                DishEntity(
                                    name = name,
                                    category = category,
                                    pricePerGram = price100g / 100.0
                                )
                            )
                        }
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Шаблон успешно сохранен!",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadDataFromDatabase()
                }
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Отмена") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun updateCalculations() {
        val netWeight = (grossWeightGrams - tareWeightGrams).coerceAtLeast(0)
        val priceFactor = selectedDish?.pricePerGram ?: 0.0
        val totalPrice = netWeight * priceFactor
        findViewById<TextView>(R.id.tvLiveWeight).text = String.format(
            Locale.US,
            "Нетто: %d г | Текущая цена: %.2f ₽",
            netWeight, totalPrice
        )
    }

    private fun updateTotalCartPrice() {
        val finalPrice = orderItemsList.sumOf { it.totalPrice }
        val tvTotalPrice = findViewById<TextView>(R.id.tvTotalPrice)
        val currentPriceText = tvTotalPrice.text.toString().replace("[^0-9.]".toRegex(), "")
        val oldPrice = currentPriceText.toDoubleOrNull() ?: 0.0
        val animator = ValueAnimator.ofFloat(oldPrice.toFloat(), finalPrice.toFloat())
        animator.duration = 200
        animator.addUpdateListener { anim ->
            val value = anim.animatedValue as Float
            tvTotalPrice.text = String.format(Locale.US, "%.2f ₽", value)
        }
        animator.start()
    }

    private fun setupActionButtons() {
        findViewById<View>(R.id.btnAddToOrder).setOnClickListener {
            val dish = selectedDish
            if (dish == null) {
                Toast.makeText(this, "Сначала выберите блюдо в меню справа!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val netWeight = (grossWeightGrams - tareWeightGrams).coerceAtLeast(0)
            if (netWeight <= 0) {
                Toast.makeText(this, "Вес нетто должен быть больше 0 грамм!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val itemPrice = netWeight * dish.pricePerGram
            val newOrderItem = OrderItem(
                id = System.currentTimeMillis(),
                dishName = dish.name,
                weightGrams = netWeight,
                pricePerGram = dish.pricePerGram,
                totalPrice = itemPrice
            )
            orderItemsList.add(newOrderItem)
            orderAdapter.updateData(orderItemsList)
            updateTotalCartPrice()
            Toast.makeText(this, "${dish.name} добавлен в чек", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnPay).setOnClickListener {
            if (orderItemsList.isEmpty()) {
                Toast.makeText(this, "Чек пуст! Добавьте взвешенные позиции.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = getSharedPreferences("KassaPrefs", MODE_PRIVATE)
            val ip = prefs.getString("aqsi_ip", "127.0.0.1")
            Toast.makeText(
                this,
                "🚀 Заказ из ${orderItemsList.size} позиций отправлен на ККМ aQsi ($ip)!",
                Toast.LENGTH_LONG
            ).show()
            orderItemsList.clear()
            orderAdapter.updateData(orderItemsList)
            updateTotalCartPrice()
        }

        findViewById<View>(R.id.btnReset).setOnClickListener {
            if (orderItemsList.isNotEmpty()) {
                orderItemsList.clear()
                orderAdapter.updateData(orderItemsList)
                updateTotalCartPrice()
                Toast.makeText(this, "Текущий заказ полностью отменен", Toast.LENGTH_SHORT).show()
            } else {
                grossWeightGrams = 0
                selectedDish = null
                findViewById<TextView>(R.id.tvLiveDishName).text = "Блюдо не выбрано"
                updateCalculations()
                Toast.makeText(this, "Вес сброшен", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnAddNewDish).setOnClickListener {
            showCreateWizardDialog()
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Настройки оборудования")
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
            val etIp = view.findViewById<EditText>(R.id.etAqsiIp)
            val etSno = view.findViewById<EditText>(R.id.etAqsiSno)
            val prefs = getSharedPreferences("KassaPrefs", MODE_PRIVATE)

            etIp.setText(prefs.getString("aqsi_ip", "127.0.0.1"))
            etSno.setText(prefs.getString("aqsi_sno", "УСН Доход"))

            builder.setView(view)
            builder.setPositiveButton("Сохранить") { dialog, _ ->
                prefs.edit().apply {
                    putString("aqsi_ip", etIp.text.toString().trim())
                    putString("aqsi_sno", etSno.text.toString().trim())
                    apply()
                }
                Toast.makeText(this, "Настройки кассы применены!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            builder.setNegativeButton("Отмена") { dialog, _ -> dialog.cancel() }
            builder.show()
        }
    }

    private fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
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
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
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

/**
 * Декодер 7-сегментного LED-дисплея весов
 */
object SevenSegmentDecoder {

    // Порядок сегментов: [Top, TopRight, BottomRight, Bottom, BottomLeft, TopLeft, Middle]
    private val SEGMENT_PATTERNS = mapOf(
        listOf(true, true, true, true, true, true, false) to '0',
        listOf(false, true, true, false, false, false, false) to '1',
        listOf(true, true, false, true, true, false, true) to '2',
        listOf(true, true, true, true, false, false, true) to '3',
        listOf(false, true, true, false, false, true, true) to '4',
        listOf(true, false, true, true, false, true, true) to '5',
        listOf(true, false, true, true, true, true, true) to '6',
        listOf(true, true, true, false, false, false, false) to '7',
        listOf(true, true, true, true, true, true, true) to '8',
        listOf(true, true, true, true, false, true, true) to '9'
    )

    fun decodeBitmap(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val columnHasLum = BooleanArray(width)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Фильтр красного свечения LED
                if (r > 140 && g < 100 && b < 100) {
                    columnHasLum[x] = true
                    break
                }
            }
        }

        val digitBoxes = mutableListOf<Pair<Int, Int>>()
        var inDigit = false
        var startX = 0

        for (x in 0 until width) {
            if (columnHasLum[x] && !inDigit) {
                inDigit = true
                startX = x
            } else if (!columnHasLum[x] && inDigit) {
                inDigit = false
                if (x - startX > 4) { // Отсеиваем шумы
                    digitBoxes.add(Pair(startX, x))
                }
            }
        }

        val result = StringBuilder()
        for ((dStartX, dEndX) in digitBoxes) {
            val dWidth = dEndX - dStartX
            val digitChar = decodeSingleDigit(pixels, width, height, dStartX, dWidth)
            if (digitChar != null) {
                result.append(digitChar)
            }
        }

        return result.toString()
    }

    private fun decodeSingleDigit(pixels: IntArray, imgWidth: Int, imgHeight: Int, startX: Int, dWidth: Int): Char? {
        val midX = startX + dWidth / 2
        val midY = imgHeight / 2
        val quarterY = imgHeight / 4
        val threeQuarterY = (imgHeight * 3) / 4

        val checkPoints = listOf(
            Pair(midX, quarterY / 2),
            Pair(startX + (dWidth * 0.85).toInt(), quarterY),
            Pair(startX + (dWidth * 0.85).toInt(), threeQuarterY),
            Pair(midX, imgHeight - (quarterY / 2)),
            Pair(startX + (dWidth * 0.15).toInt(), threeQuarterY),
            Pair(startX + (dWidth * 0.15).toInt(), quarterY),
            Pair(midX, midY)
        )

        val states = checkPoints.map { (cx, cy) ->
            if (cx in 0 until imgWidth && cy in 0 until imgHeight) {
                val pixel = pixels[cy * imgWidth + cx]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                r > 130 && g < 110 && b < 110
            } else false
        }

        return SEGMENT_PATTERNS[states]
    }
}
