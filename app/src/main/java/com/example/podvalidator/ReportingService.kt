package com.example.podvalidator

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class ReportingService(private val context: Context) {
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val queueFile: File = File(context.filesDir, "pending_reports.json")

    suspend fun submitTransaction(transaction: DeliveryTransaction): ReportSubmitResult = withContext(Dispatchers.IO) {
        appendToQueue(transaction)
        flushPendingFromQueue()
    }

    suspend fun flushPending(): ReportSubmitResult = withContext(Dispatchers.IO) {
        flushPendingFromQueue()
    }

    private fun flushPendingFromQueue(): ReportSubmitResult {
        val pending = readQueue()
        if (pending.isEmpty()) {
            return ReportSubmitResult(success = true, message = "No pending records to sync.", pendingCount = 0)
        }

        val endpoint = ApiConfig.REPORTING_WEBHOOK_URL.trim()
        if (endpoint.isBlank()) {
            return ReportSubmitResult(
                success = false,
                message = ApiConfig.WEBHOOK_NOT_CONFIGURED_HINT,
                pendingCount = pending.size
            )
        }

        return try {
            val payload = json.encodeToString(ReportEnvelope(records = pending))
            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    clearQueue()
                    ReportSubmitResult(
                        success = true,
                        message = "Synced ${pending.size} record(s) successfully.",
                        pendingCount = 0
                    )
                } else {
                    ReportSubmitResult(
                        success = false,
                        message = "Sync failed with HTTP ${response.code}. Pending queue retained.",
                        pendingCount = pending.size
                    )
                }
            }
        } catch (e: Exception) {
            val errorDetail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            ReportSubmitResult(
                success = false,
                message = "Sync failed: $errorDetail. Pending queue retained.",
                pendingCount = pending.size
            )
        }
    }

    fun buildTransaction(
        username: String,
        waybill: String,
        action: DeliveryAction,
        delivery: DeliveryPoint?,
        actionSnapshot: EventSnapshot?,
        photoSnapshot: EventSnapshot?,
        validationResult: ValidationResult,
        versionName: String
    ): DeliveryTransaction {
        return DeliveryTransaction(
            username = username,
            waybill = waybill,
            action = action.name,
            decision = if (validationResult.isGenuine) "GENUINE" else "NOT_GENUINE",
            humanDetected = validationResult.humanDetected,
            actionClickLatitude = actionSnapshot?.latitude,
            actionClickLongitude = actionSnapshot?.longitude,
            actionClickTimestampEpochMillis = actionSnapshot?.timestampEpochMillis,
            actionClickIsoTimestamp = actionSnapshot?.isoTimestamp,
            photoLatitude = photoSnapshot?.latitude,
            photoLongitude = photoSnapshot?.longitude,
            photoTimestampEpochMillis = photoSnapshot?.timestampEpochMillis,
            photoIsoTimestamp = photoSnapshot?.isoTimestamp,
            expectedLatitude = delivery?.latitude,
            expectedLongitude = delivery?.longitude,
            actionToPhotoDistanceMeters = validationResult.actionToPhotoDistanceMeters,
            actionToBackendDistanceMeters = validationResult.actionToBackendDistanceMeters,
            photoToBackendDistanceMeters = validationResult.photoToBackendDistanceMeters,
            thresholdMeters = validationResult.allowedRadiusMeters,
            validationMode = validationResult.validationMode,
            summary = validationResult.summary,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE}",
            appVersion = versionName,
            customerName = delivery?.customerName,
            address = delivery?.address
        )
    }

    private fun appendToQueue(transaction: DeliveryTransaction) {
        val existing = readQueue().toMutableList()
        existing += transaction
        queueFile.writeText(json.encodeToString(existing))
    }

    private fun readQueue(): List<DeliveryTransaction> {
        return try {
            if (!queueFile.exists() || queueFile.readText().isBlank()) emptyList()
            else json.decodeFromString(queueFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearQueue() {
        queueFile.writeText("[]")
    }
}

data class ReportSubmitResult(
    val success: Boolean,
    val message: String,
    val pendingCount: Int
)