package com.example.podvalidator

import android.content.Context
import kotlinx.serialization.json.Json

class DeliveryRepository(context: Context) {
    private val deliveries: List<DeliveryPoint>

    init {
        val json = context.assets.open("deliveries.json").bufferedReader().use { it.readText() }
        deliveries = Json { ignoreUnknownKeys = true }.decodeFromString(json)
    }

    fun findWaybill(waybill: String): DeliveryPoint? {
        return deliveries.firstOrNull { it.waybill.equals(waybill, ignoreCase = true) }
    }

    fun all(): List<DeliveryPoint> = deliveries
}