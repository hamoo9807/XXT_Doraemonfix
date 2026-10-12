package com.cofbro.qian.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cofbro.qian.R
import com.cofbro.qian.databinding.ActivityScanBinding
import com.cofbro.qian.utils.GlideEngine
import com.cofbro.qian.utils.dp2px
import com.cofbro.qian.utils.getStatusBarHeight
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.king.wechat.qrcode.WeChatQRCodeDetector
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.utils.SandboxTransformUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.OpenCV
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * 全屏扫码页 (CameraX + 微信 OpenCV 识别引擎)
 *
 * - 相机预览全屏无扫描框, 取景范围内任意位置的二维码均可识别
 * - 实时帧交给 WeChatQRCodeDetector(与相册解码同一引擎), 命中后震动并回传结果
 * - 支持手电筒开关; 相册选图解码作为补充入口
 * - 结果仍通过 Intent extra "result" 回传, 调用方(HomeFragment/TaskFragment)无需改动
 */
class ScanActivity : AppCompatActivity() {
    private companion object {
        const val TAG = "ScanActivity"
        const val REQUEST_CODE_PERMISSIONS = 1001
        const val ANALYSIS_WIDTH = 1280
        const val ANALYSIS_HEIGHT = 720
    }

    private var binding: ActivityScanBinding? = null
    private var hasCameraPermission = false
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA
    )

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchOn = false

    /** 已识别到结果, 阻止后续帧重复回调 */
    @Volatile
    private var isResultHandled = false
    private var engineNotReadyLogged = false

    private val analysisExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        binding = ActivityScanBinding.inflate(layoutInflater, null, false)
        setContentView(binding?.root)
        OpenCV.initAsync(this)
        WeChatQRCodeDetector.init(this)
        initView()
        initEvent()
        checkAndRequestPermissions()
    }

    private fun initEvent() {
        binding?.ivToPhoto?.setOnClickListener {
            toGalleryAndDecode()
        }

        binding?.ivFlash?.setOnClickListener {
            toggleTorch()
        }

        binding?.ivBack?.setOnClickListener {
            finish()
        }
    }

    private fun initView() {
        val margin = getStatusBarHeight(this) + dp2px(this, 6)
        val params = binding?.ivToPhoto?.layoutParams as? MarginLayoutParams
        params?.topMargin = margin
        binding?.ivToPhoto?.layoutParams = params
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hasCameraPermission = true
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                hasCameraPermission = true
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_LONG).show()
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                    Toast.makeText(this, "请在设置中开启相机权限", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bindUseCases(future.get())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        cameraProvider = provider

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding?.previewView?.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        imageAnalysis.setAnalyzer(analysisExecutor) { image -> analyzeFrame(image) }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
            Toast.makeText(this, "相机打开失败，请检查权限", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 逐帧识别: ImageProxy(RGBA_8888) -> Bitmap -> 微信识别引擎
     * 单线程串行执行, 配合 STRATEGY_KEEP_ONLY_LATEST 自动丢弃积压帧
     */
    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (isResultHandled) return
            val bitmap = image.toRgbaBitmap() ?: return
            val text = try {
                WeChatQRCodeDetector.detectAndDecode(bitmap).firstOrNull { !it.isNullOrBlank() }
            } catch (e: Exception) {
                // OpenCV/识别模型尚未初始化完成时忽略该帧, 后续帧会重试
                if (!engineNotReadyLogged) {
                    engineNotReadyLogged = true
                    Log.w(TAG, "识别引擎未就绪, 跳过当前帧", e)
                }
                null
            } finally {
                bitmap.recycle()
            }
            if (!text.isNullOrBlank()) {
                isResultHandled = true
                runOnUiThread {
                    vibrate()
                    sendResult(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyze frame error", e)
        } finally {
            image.close()
        }
    }

    /**
     * ImageProxy -> Bitmap
     * RGBA_8888 输出下每行可能存在 padding, 先按 rowStride 建图再裁剪
     */
    private fun ImageProxy.toRgbaBitmap(): Bitmap? {
        if (format != PixelFormat.RGBA_8888) return null
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0) return null
        val rowPadding = plane.rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        var bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.copyPixelsFromBuffer(buffer)
            if (paddedWidth != width) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                bitmap = cropped
            }
            bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            Log.w(TAG, "ImageProxy -> Bitmap failed", e)
            null
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "当前设备不支持闪光灯", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        binding?.ivFlash?.setImageResource(
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
    }

    override fun onDestroy() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun toGalleryAndDecode() {
        PictureSelector
            .create(this)
            .openGallery(SelectMimeType.ofImage())
            .setSandboxFileEngine { context, srcPath, mineType, call ->
                if (call != null) {
                    val sandboxPath =
                        SandboxTransformUtils.copyPathToSandbox(context, srcPath, mineType)
                    call.onCallback(srcPath, sandboxPath)
                }
            }
            .setImageEngine(GlideEngine.createGlideEngine())
            .setMaxSelectNum(1)
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>?) {
                    result?.get(0)?.let { media ->
                        val path = media.availablePath
                        decodeFromPath(path)
                    }
                }

                override fun onCancel() {}
            })
    }

    private fun decodeFromPath(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = loadBitmapFromPath(path)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from: $path")
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@ScanActivity, "图片加载失败", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            var result: String? = null
            try {
                val wxResults = WeChatQRCodeDetector.detectAndDecode(bitmap)
                result = wxResults.getOrNull(0)
                Log.d(TAG, "WeChatQRCode result: $result")
            } catch (e: Exception) {
                Log.w(TAG, "WeChatQRCode detect failed", e)
            }
            if (result.isNullOrEmpty()) {
                result = decodeWithZXing(bitmap)
                Log.d(TAG, "ZXing fallback result: $result")
            }
            val finalResult = result
            lifecycleScope.launch(Dispatchers.Main) {
                if (finalResult.isNullOrEmpty()) {
                    Toast.makeText(this@ScanActivity, "未识别到二维码", Toast.LENGTH_SHORT).show()
                } else {
                    sendResult(finalResult)
                }
            }
        }
    }

    private fun loadBitmapFromPath(path: String): Bitmap? {
        return try {
            if (path.startsWith("content://")) {
                contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                    BufferedInputStream(input).use { bis ->
                        BitmapFactory.decodeStream(bis)
                    }
                }
            } else {
                val file = File(path)
                if (file.exists()) {
                    BitmapFactory.decodeFile(path)
                } else {
                    contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmapFromPath error", e)
            null
        }
    }

    private fun decodeWithZXing(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            Log.w(TAG, "ZXing decode failed", e)
            null
        }
    }

    private fun sendResult(result: String?) {
        val intent = Intent()
        intent.putExtra("result", result)
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(200)
    }
}
