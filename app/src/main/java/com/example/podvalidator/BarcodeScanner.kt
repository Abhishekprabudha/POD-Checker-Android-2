package com.example.podvalidator

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

suspend fun scanWaybillFromBitmap(bitmap: Bitmap): String? {
    val scanner = BarcodeScanning.getClient()
    val image = InputImage.fromBitmap(bitmap, 0)
    return try {
        val barcodes = scanner.process(image).await()
        barcodes.firstOrNull()?.rawValue?.trim()?.uppercase()
    } finally {
        scanner.close()
    }
}