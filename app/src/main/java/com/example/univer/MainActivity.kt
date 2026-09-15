package com.example.univer

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var lastOcrTime = 0L

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

    // УНИВЕРСАЛЬНЫЙ ДИАЛОГ СОЗДАНИЯ (ШАБЛОНЫ: БЛЮДО ИЛИ ТАРА)
    private fun showCreateWizardDialog() {
        val builder = AlertDialog.Builder(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_dish, null)

        val radioGroup = view.findViewById<RadioGroup>(R.id.rgCreateType)
        val etName = view.findViewById<EditText>(R.id.etNewDishName)
        val etCategory = view.findViewById<EditText>(R.id.etNewDishCategory)
        val etValue = view.findViewById<EditText>(R.id.etNewDishPrice100g)

        // По умолчанию выбран тип "Блюдо" — категория видна
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
        // КНОПКА "В ЧЕК +"
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

        // ОПЛАТА ВСЕГО ЗАКАЗА
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

        // ОТМЕНА ВСЕГО ЧЕКА
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
