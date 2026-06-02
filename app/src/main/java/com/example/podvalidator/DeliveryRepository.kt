package com.example.podvalidator

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DeliveryRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val customDeliveryFile = File(context.filesDir, "delivery_waybill_database.json")
    private val assetDeliveries: List<DeliveryPoint>
    private var customDeliveries: MutableList<DeliveryPoint>

    init {
        val assetJson = context.assets.open("deliveries.json").bufferedReader().use { it.readText() }
        assetDeliveries = json.decodeFromString(assetJson)
        customDeliveries = readCustomDeliveries().toMutableList()
    }

    fun findWaybill(waybill: String): DeliveryPoint? {
        val normalizedWaybill = waybill.normalizedWaybill()
        if (normalizedWaybill.isBlank()) return null

        return customDeliveries.firstOrNull { it.waybill.equals(normalizedWaybill, ignoreCase = true) }
            ?: assetDeliveries.firstOrNull { it.waybill.equals(normalizedWaybill, ignoreCase = true) }
    }

    fun getOrCreateWaybill(waybill: String, username: String): DeliveryPoint? {
        val normalizedWaybill = waybill.normalizedWaybill()
        if (normalizedWaybill.isBlank()) return null

        val existing = findWaybill(normalizedWaybill)
        if (existing != null) {
            val normalizedUsername = username.normalizedUsername()
            return upsertCustomDelivery(
                existing.copy(
                    waybill = normalizedWaybill,
                    mappedUsername = normalizedUsername
                        .ifBlank { existing.mappedUsername.orEmpty() }
                        .takeIf { it.isNotBlank() },
                    lastUpdatedIsoTimestamp = currentIsoTimestamp()
                )
            )
        }

        return upsertCustomDelivery(
            DeliveryPoint(
                waybill = normalizedWaybill,
                customerName = "Resi ad hoc",
                address = "Dibuat dari input atau pemindaian aplikasi",
                mappedUsername = username.normalizedUsername().takeIf { it.isNotBlank() },
                lastUpdatedIsoTimestamp = currentIsoTimestamp()
            )
        )
    }

    fun updateFromTransaction(transaction: DeliveryTransaction): DeliveryPoint? {
        val existing = getOrCreateWaybill(transaction.waybill, transaction.username) ?: return null
        val bestLatitude = transaction.photoLatitude ?: transaction.actionClickLatitude ?: existing.latitude
        val bestLongitude = transaction.photoLongitude ?: transaction.actionClickLongitude ?: existing.longitude

        return upsertCustomDelivery(
            existing.copy(
                mappedUsername = transaction.username.normalizedUsername()
                    .ifBlank { existing.mappedUsername.orEmpty() }
                    .takeIf { it.isNotBlank() },
                latitude = bestLatitude,
                longitude = bestLongitude,
                lastAction = transaction.action,
                lastDecision = transaction.decision,
                lastUpdatedIsoTimestamp = transaction.photoIsoTimestamp
                    ?: transaction.actionClickIsoTimestamp
                    ?: currentIsoTimestamp()
            )
        )
    }

    fun all(): List<DeliveryPoint> = assetDeliveries + customDeliveries

    private fun upsertCustomDelivery(delivery: DeliveryPoint): DeliveryPoint {
        val existingIndex = customDeliveries.indexOfFirst { it.waybill.equals(delivery.waybill, ignoreCase = true) }
        val merged = delivery.copy(waybill = delivery.waybill.normalizedWaybill())
        if (existingIndex >= 0) {
            customDeliveries[existingIndex] = merged
        } else {
            customDeliveries += merged
        }
        writeCustomDeliveries()
        return merged
    }

    private fun readCustomDeliveries(): List<DeliveryPoint> {
        return try {
            if (!customDeliveryFile.exists() || customDeliveryFile.readText().isBlank()) emptyList()
            else json.decodeFromString(customDeliveryFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeCustomDeliveries() {
        customDeliveryFile.writeText(json.encodeToString(customDeliveries))
    }
}

private fun String.normalizedWaybill(): String = trim().uppercase()

private fun String.normalizedUsername(): String = trim()

private fun currentIsoTimestamp(): String {
    val now = System.currentTimeMillis()
    return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
        .format(java.util.Date(now))
}
