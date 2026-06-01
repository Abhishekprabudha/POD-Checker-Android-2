package com.example.podvalidator

import kotlinx.serialization.Serializable

@Serializable
data class DeliveryPoint(
    val waybill: String,
    val customerName: String,
    val address: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val allowedRadiusMeters: Double = 100.0
)

enum class DeliveryAction {
    DELIVERED,
    NON_DELIVERED
}

@Serializable
data class EventSnapshot(
    val latitude: Double?,
    val longitude: Double?,
    val timestampEpochMillis: Long?,
    val isoTimestamp: String?
)

@Serializable
data class ValidationResult(
    val isGenuine: Boolean,
    val humanDetected: Boolean,
    val validationMode: String,
    val actionClickLatitude: Double?,
    val actionClickLongitude: Double?,
    val actionClickTimestampEpochMillis: Long?,
    val photoLatitude: Double?,
    val photoLongitude: Double?,
    val photoTimestampEpochMillis: Long?,
    val expectedLatitude: Double?,
    val expectedLongitude: Double?,
    val actionToPhotoDistanceMeters: Double?,
    val actionToBackendDistanceMeters: Double?,
    val photoToBackendDistanceMeters: Double?,
    val allowedRadiusMeters: Double,
    val summary: String
)

@Serializable
data class DeliveryTransaction(
    val username: String,
    val waybill: String,
    val action: String,
    val decision: String,
    val humanDetected: Boolean,
    val actionClickLatitude: Double?,
    val actionClickLongitude: Double?,
    val actionClickTimestampEpochMillis: Long?,
    val actionClickIsoTimestamp: String?,
    val photoLatitude: Double?,
    val photoLongitude: Double?,
    val photoTimestampEpochMillis: Long?,
    val photoIsoTimestamp: String?,
    val expectedLatitude: Double?,
    val expectedLongitude: Double?,
    val actionToPhotoDistanceMeters: Double?,
    val actionToBackendDistanceMeters: Double?,
    val photoToBackendDistanceMeters: Double?,
    val thresholdMeters: Double,
    val validationMode: String,
    val summary: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val customerName: String? = null,
    val address: String? = null
)

@Serializable
data class ReportEnvelope(
    val spreadsheetTarget: String = "JNE Courier POD Report",
    val records: List<DeliveryTransaction>
)

@Serializable
data class ReportAck(
    val success: Boolean = false,
    val message: String = ""
)