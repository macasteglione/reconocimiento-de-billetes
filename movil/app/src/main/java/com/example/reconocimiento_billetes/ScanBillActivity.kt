package com.example.reconocimiento_billetes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.SystemClock
import android.os.Vibrator
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.reconocimiento_billetes.data.SQLiteHelper
import com.example.reconocimiento_billetes.presentation.CameraPreview
import com.example.reconocimiento_billetes.presentation.getLocalizedAudioResId
import com.example.reconocimiento_billetes.presentation.loadClassNames
import com.example.reconocimiento_billetes.presentation.loadModel
import com.example.reconocimiento_billetes.ui.theme.ReconocimientobilletesTheme
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.min

class ScanBillActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val imageSize = 224
    private var scanningDialog: AlertDialog? = null
    private lateinit var vibrator: Vibrator
    private lateinit var controller: LifecycleCameraController
    private lateinit var selectedModel: String

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaPlayer = MediaPlayer.create(this, getLocalizedAudioResId(this, "escaneo_billetes"))
        mediaPlayer?.start()

        if (!hasCameraPermission()) ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), 0
        )

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(
                CameraController.IMAGE_CAPTURE
            )
        }

        selectedModel = intent.getStringExtra("selectedModel").toString()

        setContent {
            ReconocimientobilletesTheme {
                var offsetX by remember { mutableFloatStateOf(0f) }

                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                val percentage = 0.37f
                val thresholdWidth = with(LocalDensity.current) { screenWidth.toPx() * percentage }

                val scaffoldState = rememberBottomSheetScaffoldState()

                BottomSheetScaffold(scaffoldState = scaffoldState,
                    sheetPeekHeight = 0.dp,
                    sheetContent = {}) { padding ->
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = {
                                if (offsetX > -thresholdWidth) {
                                    offsetX = 0f
                                }
                            }, onDragCancel = {
                                offsetX = 0f
                            }) { _, dragAmount ->
                                offsetX += dragAmount.x
                                if (offsetX < -thresholdWidth) finish()
                            }
                        }) {
                        CameraPreview(
                            controller = controller, modifier = Modifier.fillMaxSize()
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(16.dp), horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            IconButton(onClick = {
                                showScanningDialog()
                                takePhoto(controller)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Take photo"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        controller.unbind()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && action == KeyEvent.ACTION_DOWN) {
            showScanningDialog()
            takePhoto(controller)
        }

        return super.dispatchKeyEvent(event)
    }

    private fun takePhoto(
        controller: LifecycleCameraController
    ) {
        controller.enableTorch(true)
        SystemClock.sleep(2000)

        controller.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    super.onCaptureSuccess(image)
                    controller.enableTorch(false)

                    val matrix = Matrix().apply {
                        postRotate(image.imageInfo.rotationDegrees.toFloat())
                    }

                    var bitmap = Bitmap.createBitmap(
                        image.toBitmap(), 0, 0, image.width, image.height, matrix, true
                    )

                    val dimension = min(bitmap.width, bitmap.height)
                    bitmap = ThumbnailUtils.extractThumbnail(bitmap, dimension, dimension)
                    bitmap = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, false)

                    try {
                        val tempFile = File.createTempFile("temp_image", ".jpg", cacheDir)
                        val fos = FileOutputStream(tempFile)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                        fos.flush()
                        fos.close()

                        /*val filePath = tempFile.absolutePath

                        val py = Python.getInstance()
                        val myModule = py.getModule("main")
                        val classifyImage = myModule["classify_image"]

                        val result = classifyImage?.call(
                            filePath, hashMapOf(
                                "API_URL" to getString(R.string.API_URL),
                                "API_KEY" to getString(R.string.API_KEY)
                            )
                        )*/

                        val result = clasificadorModelo(bitmap, selectedModel)

                        scanningDialog?.dismiss()
                        if (result != null) {
                            showResultDialog("${getString(R.string.billeteDetectado)}$result")
                            playDenominationSound(result.toString(), selectedModel)
                            guardarBaseDeDatos(result.toString())
                        } else {
                            showResultDialog(getString(R.string.noSeDetectoBillete))
                            playDenominationSound(result.toString(), selectedModel)
                        }

                        tempFile.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        scanningDialog?.dismiss()
                        errorMsg()
                    } finally {
                        image.close()
                    }
                }
            })
    }

    private fun clasificadorModelo(image: Bitmap, modelName: String): String? {
        val model = loadModel(modelName, this)

        val inputFeature0 =
            TensorBuffer.createFixedSize(intArrayOf(1, imageSize, imageSize, 3), DataType.FLOAT32)

        val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(imageSize * imageSize)
        image.getPixels(intValues, 0, image.width, 0, 0, image.width, image.height)
        var pixel = 0

        for (i in 0 until imageSize)
            for (j in 0 until imageSize) {
                val `val` = intValues[pixel++]
                byteBuffer.putFloat(((`val` shr 16) and 0xFF) * (1f / 1))
                byteBuffer.putFloat(((`val` shr 8) and 0xFF) * (1f / 1))
                byteBuffer.putFloat((`val` and 0xFF) * (1f / 1))
            }

        inputFeature0.loadBuffer(byteBuffer)

        val outputs = model.process(inputFeature0)
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer
        val confidences = outputFeature0.floatArray

        var maxPos = 0
        var maxConfidence = 0f
        for (i in confidences.indices)
            if (confidences[i] > maxConfidence) {
                maxConfidence = confidences[i]
                maxPos = i
            }

        val classes = loadClassNames(this, modelName)
        model.close()

        return if (maxConfidence >= .85) classes[maxPos] else null
    }


    private fun errorMsg() {
        showResultDialog(getString(R.string.errorProcesarImagen))
        mediaPlayer = MediaPlayer.create(this, getLocalizedAudioResId(this, "error"))
        mediaPlayer?.start()
    }

    private fun vibrateDevice(duration: Long = 300L) {
        if (vibrator.hasVibrator()) vibrator.vibrate(duration)
    }

    private fun showScanningDialog() {
        vibrateDevice()
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setTitle(getString(R.string.escaneando))
        dialogBuilder.setMessage(getString(R.string.esperaProcesoImagen))
        dialogBuilder.setCancelable(false)

        scanningDialog = dialogBuilder.create()
        scanningDialog?.show()

        mediaPlayer = MediaPlayer.create(this, getLocalizedAudioResId(this, "escaneando"))
        mediaPlayer?.start()
    }

    private fun guardarBaseDeDatos(billete: String) {
        val db = SQLiteHelper(this)
        db.insertBill(billete.toInt(), getCurrentDateTime())
    }

    private fun getCurrentDateTime(): String {
        val currentDateTime = Calendar.getInstance().time
        val dateFormat = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.DEFAULT,
            java.text.DateFormat.DEFAULT
        )
        return dateFormat.format(currentDateTime)
    }

    private fun showResultDialog(resultText: String) {
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setTitle(getString(R.string.resultadoClasificacion))
        dialogBuilder.setMessage(resultText)
        dialogBuilder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

        val alertDialog = dialogBuilder.create()
        alertDialog.show()
    }

    private fun playDenominationSound(denomination: String, modelName: String) {
        var audioResId = getLocalizedAudioResId(this, "no_se_detecto")
        val localizedAudioResId =
            getLocalizedAudioResId(this, "answer_${denomination}_${modelName}")

        if (localizedAudioResId != 0) audioResId = localizedAudioResId

        audioResId.let {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, it)
            mediaPlayer?.start()
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}