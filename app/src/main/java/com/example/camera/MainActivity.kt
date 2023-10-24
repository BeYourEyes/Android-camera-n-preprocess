package com.example.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.max
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Math.pow
import java.lang.Math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.Logger
import kotlin.math.max

class MainActivity : AppCompatActivity() {



    // 권한 처리에 필요한 변수
    val CAMERA_PERMISSION = arrayOf(Manifest.permission.CAMERA)
    val STORAGE_PERMISSION = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE
        , Manifest.permission.WRITE_EXTERNAL_STORAGE)

    val FLAG_PERM_CAMERA = 98
    val FLAG_PERM_STORAGE = 99

    val FLAG_REQ_CAMERA = 101
    val BLUR_SCORE_THRESH = 50

    lateinit var imagePreview : ImageView
    lateinit var textview : TextView
    lateinit var switchbtn : Switch

    lateinit var currentPhotoPath: String

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imagePreview = findViewById<ImageView>(R.id.imageView)
        textview = findViewById<TextView>(R.id.textView)
        switchbtn = findViewById<Switch>(R.id.crop_switch)

        // 카메라
        val camera = findViewById<Button>(R.id.btn_camera)
        camera.setOnClickListener {
            if(isPermission(CAMERA_PERMISSION)){
                dispatchTakePictureIntent()
            } else {
                ActivityCompat.requestPermissions(this, CAMERA_PERMISSION, FLAG_PERM_CAMERA)
            }
        }

        //openCV
        OpenCVLoader.initDebug()

    }

    fun isPermission(permissions:Array<String>) : Boolean {

        for(permission in permissions) {
            val result = ContextCompat.checkSelfPermission(this, permission)
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            // Ensure that there's a camera activity to handle the intent
            takePictureIntent.resolveActivity(packageManager)?.also {
                // Create the File where the photo should go
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    // Error occurred while creating the File
                    null
                }
                // Continue only if the File was successfully created
                photoFile?.also {
                    val photoURI: Uri = FileProvider.getUriForFile(
                        this,
                        "com.example.camera.fileprovider",
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    startActivityForResult(takePictureIntent, FLAG_REQ_CAMERA)
                }
            }
        }
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(resultCode == Activity.RESULT_OK){
            when(requestCode) {
                FLAG_REQ_CAMERA -> {
                    textview.text= "checked"

                    val f = File(currentPhotoPath)
                    val uri = Uri.fromFile(f)

                    val bitmap: Bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri)
                    if (bitmap != null) {
                        val blur_score = getBlurScore(bitmap)
                        textview.text = "blur score: " + blur_score
                        if ( blur_score < BLUR_SCORE_THRESH) {
                            imagePreview.setImageBitmap(bitmap)
                            textview.text = textview.text.toString() + "\n이미지가 흐립니다. 다시 촬영해주세요"
                        }else{
                            val box =  getDetectionResult(bitmap)
                            if (box != null && switchbtn.isChecked){
                                textview.text = textview.text.toString() + "\n" + box.left.toString() + ", " + box.top.toString() + ", " + box.width().toString() + ", " + box.height().toString()

                                val output = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
                                imagePreview.setImageBitmap(output)
                            }
                        }
                    }else{
                        Toast.makeText(this, "사진 촬영에 실패하였습니다.", Toast.LENGTH_LONG).show()
                    }
                    f.delete()
                }
            }
        }
    }
    private fun getBlurScore(bitmap: Bitmap): Double {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val src = Mat()
        Utils.bitmapToMat(outputBitmap, src)
        // to grayscale
        val graySrc = Mat()
        Imgproc.cvtColor(src, graySrc, Imgproc.COLOR_BGR2GRAY)
        val laplacian = Mat()
        Imgproc.Laplacian(graySrc, laplacian, CvType.CV_64F)

        // Calculate the variance of the Laplacian
        val variance = Mat()
        Core.multiply(laplacian, laplacian, variance)
        val mean = Core.mean(variance)
        return mean.`val`[0]
    }

    private fun getDetectionResult(
        bitmap: Bitmap
    ): Rect? {

        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val h = bitmap.height
        val w = bitmap.width

        // Set the area threshold as 1% of the image size
        val areaThreshold = h * w * 0.0001
        val h_thresh = h * 0.05
        val w_thresh = w * 0.05
        val src = Mat()
        Utils.bitmapToMat(outputBitmap, src)

        // to grayscale
        val graySrc = Mat()
        Imgproc.cvtColor(src, graySrc, Imgproc.COLOR_BGR2GRAY)

        val histEqlSrc = Mat()
        Imgproc.equalizeHist(graySrc, histEqlSrc)

        // to binary
        val binarySrc = Mat()
        Imgproc.threshold(histEqlSrc, binarySrc, 0.0, 255.0, Imgproc.THRESH_OTSU)
        // Core.bitwise_not(binarySrc, binarySrc)

        val lineMinWidth = 1
        val kernelH = Mat(1, lineMinWidth, CvType.CV_8U)
        val kernelV = Mat(lineMinWidth, 1, CvType.CV_8U)

        // Extract horizontal and vertical lines
        val imgBinH = Mat()
        val imgBinV = Mat()
        Imgproc.morphologyEx(binarySrc, imgBinH, Imgproc.MORPH_OPEN, kernelH)
        Imgproc.morphologyEx(binarySrc, imgBinV, Imgproc.MORPH_OPEN, kernelV)

        // Combine horizontal and vertical lines
        val imgBinFinal = Mat()
        Core.bitwise_or(imgBinH, imgBinV, imgBinFinal)

        // Dilate the lines
        val finalKernel = Mat(2, 2, CvType.CV_8U)
        Imgproc.dilate(imgBinFinal, imgBinFinal, finalKernel, Point(-1.0, -1.0), 1)

        // Connected component labeling
        val connectedComponents = Mat()
        val stats = Mat()
        val centroids = Mat()
        Imgproc.connectedComponentsWithStats(imgBinFinal, connectedComponents, stats, centroids, 8, CvType.CV_32S)

        val filteredStats = Mat()
        for (i in 0 until stats.rows()) {

            // Exclude incorrectly extracted boxes (too small, edge boxes, or whole image)
            val area = stats[i, 4][0]
            if (area < areaThreshold)
                continue
            val x = stats[i, 0][0]
            val y = stats[i, 1][0]
            val width = stats[i, 2][0]
            val height = stats[i, 3][0]
            if (x <= w_thresh || y <= h_thresh || x + width >= w - w_thresh || y + height >= h - h_thresh)
                continue
            filteredStats.push_back(stats.row(i))
        }

        if(filteredStats.rows() == 0) {
            Utils.matToBitmap(src, outputBitmap)
            textview.text = textview.text.toString() + "\nnothing left after filtering"
            return null
        }

        val minMaxLocResult = Core.minMaxLoc(filteredStats.col(0))
        val left = minMaxLocResult.minVal
        val minMaxLocResultY = Core.minMaxLoc(filteredStats.col(1))
        val top = minMaxLocResultY.minVal

        val maxBoxXcols = Mat()
        Core.add(filteredStats.col(0), filteredStats.col(2), maxBoxXcols)
        val minMaxLocResultRight = Core.minMaxLoc(maxBoxXcols.col(0))
        val right = minMaxLocResultRight.maxVal

        val maxBoxYcols = Mat()
        Core.add(filteredStats.col(1), filteredStats.col(3), maxBoxYcols)
        val minMaxLocResultBottom = Core.minMaxLoc(maxBoxYcols.col(0))
        val bottom = minMaxLocResultBottom.maxVal

        Imgproc.rectangle(src, Point(left, top), Point(right, bottom), Scalar(0.0, 0.0, 255.0), 10)
        Utils.matToBitmap(src, outputBitmap)
        imagePreview.setImageBitmap(outputBitmap)

        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    // 평면상 두 점 사이의 거리는 직각삼각형의 빗변길이 구하기와 동일
    private fun calculateMaxWidthHeight(
        tl:Point,
        tr:Point,
        br:Point,
        bl:Point,
    ): Size {
        // Calculate width
        val widthA = sqrt((tl.x - tr.x) * (tl.x - tr.x) + (tl.y - tr.y) * (tl.y - tr.y))
        val widthB = sqrt((bl.x - br.x) * (bl.x - br.x) + (bl.y - br.y) * (bl.y - br.y))
        val maxWidth = max(widthA, widthB)
        // Calculate height
        val heightA = sqrt((tl.x - bl.x) * (tl.x - bl.x) + (tl.y - bl.y) * (tl.y - bl.y))
        val heightB = sqrt((tr.x - br.x) * (tr.x - br.x) + (tr.y - br.y) * (tr.y - br.y))
        val maxHeight = max(heightA, heightB)
        return Size(maxWidth, maxHeight)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        //Log.d(TAG, "onRequestPermissionsResult");
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            //Log.d(TAG, "Permission: " + permissions[0] + "was " + grantResults[0]); }
        }
    }
}