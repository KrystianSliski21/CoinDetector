package com.example.grosz2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors



class MainActivity : AppCompatActivity() {

    private lateinit var interpreter: Interpreter
    private lateinit var labels: List<String>
    private val imageSize = 224
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var imageView: ImageView
    private lateinit var resultTextView: TextView
    private lateinit var closeButton: Button

    //uruchamianie aktywności i inicjalizacja widoków
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        imageView = findViewById(R.id.imageView)
        resultTextView = findViewById(R.id.resultTextView)
        closeButton = findViewById(R.id.closeButton)
        closeButton.setOnClickListener {
            resetView()
        }

        // załadowanie modelu i etykiety
        interpreter = Interpreter(loadModelFile(this))
        labels = loadLabels()

        // żądanie zezwolenia dostępu do kamery
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        val takePhotoButton = findViewById<Button>(R.id.takePhotoButton)
        takePhotoButton.setOnClickListener {
            takePhoto()
        }

        val selectImageButton = findViewById<Button>(R.id.selectImageButton)
        selectImageButton.setOnClickListener {
            selectImage()
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build()
            imageCapture = ImageCapture.Builder().build()

            preview.setSurfaceProvider(previewView.surfaceProvider)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    //załadowanie modelu tensorflowlite
    private fun loadModelFile(activity: Activity): MappedByteBuffer {
        val fileDescriptor = activity.assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    //wczytanie etykiet
    private fun loadLabels(): List<String> {
        return assets.open("labels.txt").bufferedReader().use { it.readLines() }
    }
    //wybór obrazu z galerii
    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, 1)
    }
    //przetworzenie wyboru zdjęcia z galerii i wykonanie klasyfikacji
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val uri = data.data //uzyskanie URI wybranego obrazu z obiektu Intent (data).
            val inputStream = contentResolver.openInputStream(uri!!) // otworzenie strumienia wejściowego (InputStream) do odczytu danych z uzyskanego URI
            val bitmap = BitmapFactory.decodeStream(inputStream) //przekształcenie strumienia do obiektu bitmapy
            previewView.visibility = android.view.View.GONE //ustawianie widoczności aktywności takich jak podgląd i zamknięcie podglądu
            imageView.visibility = android.view.View.VISIBLE
            imageView.setImageBitmap(bitmap)
            closeButton.visibility = android.view.View.VISIBLE
            resultTextView.visibility = android.view.View.VISIBLE
            classifyImage(bitmap) //wywołanie metody classifyImage by sklasyfikować zdjęcie
        }
    }

    private fun takePhoto() {
        //tworzenie pliku
        val photoFile = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
        //tworzenie obiektu OutputFileOptions dla ImageCapture, używając obiektu Builder.
        //OutputFileOptions określa, gdzie i jak mają być przechowywane zdjęcia przechwycone przez kamerę
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            //Opcje pliku wyjściowego określające miejsce zapisu zdjęcia.
            //ImageCapture.OnImageSavedCallback,  obsługuje zdarzenia zapisu obrazu.
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                }
                //nadpisanie onImageSaved interfejsu OnImageSavedCallback
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    val rotatedBitmap = rotateImage(bitmap, 90)

                    // Przycięcie uwzględniające proporcje
                    val croppedBitmap = cropToFrame(rotatedBitmap)

                    // Wyświetlenie przyciętej bitmapy
                    previewView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    imageView.setImageBitmap(croppedBitmap)
                    closeButton.visibility = View.VISIBLE
                    resultTextView.visibility = View.VISIBLE

                    // Klasyfikacja obrazu
                    classifyImage(croppedBitmap)
                }

            })
    }
    //klasyfikacja zdjęcia, przetwarzanie obrazu do bitmapy o rozmiarach 224x224 i normalizacja
    private fun classifyImage(bitmap: Bitmap) {
        //Tworzenie nowy bitmapy przeskalowanej do rozmiaru imageSize x imageSize (224x224 pikseli).
        //przeskalowanie do rozmiaru, którego oczekuje model.
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)

        // Normalizacja obrazu do zakresu [-1, 1]
        //Każdy piksel w obrazie jest reprezentowany przez trzy wartości (R, G, B),
        // stąd całkowita liczba elementów w tablicy wynosi imageSize * imageSize * 3.
        val floatValues = FloatArray(imageSize * imageSize * 3)
        val pixels = IntArray(imageSize * imageSize)
        resizedBitmap.getPixels(pixels, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            floatValues[i * 3 + 0] = ((pixel shr 16 and 0xFF) / 127.5f) - 1.0f //kanał czerwony
            floatValues[i * 3 + 1] = ((pixel shr 8 and 0xFF) / 127.5f) - 1.0f //kanał zielony
            floatValues[i * 3 + 2] = ((pixel and 0xFF) / 127.5f) - 1.0f // kanał niebieski
        }

        val inputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, imageSize, imageSize, 3), org.tensorflow.lite.DataType.FLOAT32)
        inputBuffer.loadArray(floatValues)

        val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, labels.size), org.tensorflow.lite.DataType.FLOAT32)

        //przeprowadzanie wnioskowania na modelu tensorflow
        interpreter.run(inputBuffer.buffer, outputBuffer.buffer.rewind())

        //Przypisywanie etykiet do wyników
        val labeledProbability = TensorLabel(labels, outputBuffer).mapWithFloatValue
        //Znajdowanie maksymalnej wartości
        val maxEntry = labeledProbability.maxByOrNull { it.value }
        //Wyświetlanie wyników
        val resultText = "Wykryta moneta: ${maxEntry?.key} \nDokładność: ${"%.2f".format(maxEntry?.value?.times(100))}%"
        resultTextView.text = resultText
    }
    //reset widoku po zamknęciu wyświetlonego zdjęcia
    private fun resetView() {
        previewView.visibility = android.view.View.VISIBLE
        imageView.visibility = android.view.View.GONE
        closeButton.visibility = android.view.View.GONE
        resultTextView.visibility = android.view.View.GONE
        resultTextView.text = ""
        startCamera()
    }
    //żądanie zezwolenia
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            // Nieuzyskano zezwolenia
        }
    }
    //obrót zdjęcia
    private fun rotateImage(img: Bitmap, degree: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degree.toFloat())
        return Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
    }
    //przycinanie do obszaru
    private fun cropToFrame(bitmap: Bitmap): Bitmap {
        // wymiary bitmapy
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        // wymiary podglądu
        val previewWidth = previewView.width
        val previewHeight = previewView.height

        // wymiary ramki w podglądzie
        val frameSizePx = 224
        val frameLeftPx = (previewWidth - frameSizePx) / 2
        val frameTopPx = (previewHeight - frameSizePx) / 2

        // skalowanie bitmapy względem podglądu
        val scaleWidth = bitmapWidth.toFloat() / previewWidth
        val scaleHeight = bitmapHeight.toFloat() / previewHeight

        //współrzędne ramki na bitmapie
        val cropLeft = (frameLeftPx * scaleWidth).toInt()
        val cropTop = (frameTopPx * scaleHeight).toInt()
        val cropWidth = (frameSizePx * scaleWidth).toInt()
        val cropHeight = (frameSizePx * scaleHeight).toInt()

        // dostosowanie przycięcia do rozmiarów bitmapy
        val safeCropLeft = cropLeft.coerceAtLeast(0)
        val safeCropTop = cropTop.coerceAtLeast(0)
        val safeCropWidth = cropWidth.coerceAtMost(bitmapWidth - safeCropLeft)
        val safeCropHeight = cropHeight.coerceAtMost(bitmapHeight - safeCropTop)

        // przycięcie bitmapy
        return Bitmap.createBitmap(bitmap, safeCropLeft, safeCropTop, safeCropWidth, safeCropHeight)
    }






}
