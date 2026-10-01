package com.pittech.devices

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Static protocol used by the GrillirG Control app for iFireTech Wi-Fi setup. */
internal object GrillirGProtocol {
    const val SERVICE_UUID = "000000ff-0000-1000-8000-00805f9b34fb"
    const val WRITE_NOTIFY_CHARACTERISTIC_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
    const val DEVICE_ID_SERVICE_UUID = "000000fe-0000-1000-8000-00805f9b34fb"
    const val DEVICE_ID_CHARACTERISTIC_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
    const val ADVERTISEMENT_DEVICE_ID_UUID = "000002ff-0000-1000-8000-00805f9b34fb"

    data class Frame(
        val version: Int,
        val command: Int,
        val payload: ByteArray,
    )

    data class WifiNetwork(
        val signalIndicator: Int,
        val rssiRaw: Int,
        val authMode: Int,
        val cipher: Int,
        val groupCipher: Int,
        val primaryChannel: Int,
        val bssid: ByteArray,
        val ssid: String,
    ) {
        val key: String get() = bssid.toHex()
        val isOpen: Boolean get() = authMode == 0
    }

    fun verificationFrame(): ByteArray = encodeFrame(version = 1, command = 5, payload = byteArrayOf(0xA5.toByte()))

    fun wifiScanRequestFrame(): ByteArray = encodeFrame(version = 1, command = 1, payload = byteArrayOf(1))

    /** Splits a protocol frame into the default 20-byte ATT values used by the vendor BLE client. */
    fun splitForGattWrites(frame: ByteArray): List<ByteArray> {
        require(frame.isNotEmpty()) { "A BLE frame cannot be empty." }
        return (frame.indices step DEFAULT_GATT_WRITE_BYTES).map { offset ->
            frame.copyOfRange(offset, minOf(frame.size, offset + DEFAULT_GATT_WRITE_BYTES))
        }
    }

    fun encodeFrame(version: Int, command: Int, payload: ByteArray): ByteArray {
        require(version in 0..255) { "Version must fit in one byte." }
        require(command in 0..255) { "Command must fit in one byte." }
        require(payload.size <= 255) { "Payload is too large for the controller frame." }

        val bytesBeforeCrc = byteArrayOf(
            command.toByte(),
            payload.size.toByte(),
        ) + payload
        val crc = crc8(bytesBeforeCrc)
        return byteArrayOf(START, version.toByte(), command.toByte(), payload.size.toByte()) +
            payload + byteArrayOf(crc.toByte(), END)
    }

    fun parseFrame(value: ByteArray): Frame? {
        val start = value.indexOf(START)
        if (start < 0 || start + 5 >= value.size) return null
        val end = value.lastIndexOf(END)
        if (end <= start + 4) return null
        val payloadLength = value[start + 3].toInt() and 0xFF
        if (end - start + 1 != payloadLength + FRAME_OVERHEAD) return null

        val crcInput = value.copyOfRange(start + 2, end - 1)
        val expectedCrc = value[end - 1].toInt() and 0xFF
        if (crc8(crcInput) != expectedCrc) return null

        return Frame(
            version = value[start + 1].toInt() and 0xFF,
            command = value[start + 2].toInt() and 0xFF,
            payload = value.copyOfRange(start + 4, end - 1),
        )
    }

    fun parseWifiNetwork(payload: ByteArray): WifiNetwork? {
        // A one-byte [1] command-2 payload is the vendor app's scan-complete marker.
        if (payload.size < WIFI_RECORD_PREFIX_BYTES || (payload.size == 1 && payload[0].u8() == 1)) return null
        val bssid = payload.copyOfRange(6, 12)
        val ssidLength = payload[12].u8()
        if (ssidLength == 0 || payload.size < WIFI_RECORD_PREFIX_BYTES + ssidLength) return null
        val ssidBytes = payload.copyOfRange(WIFI_RECORD_PREFIX_BYTES, WIFI_RECORD_PREFIX_BYTES + ssidLength)
        val ssid = runCatching {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(ssidBytes)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return WifiNetwork(
            signalIndicator = payload[0].u8(),
            rssiRaw = payload[1].u8(),
            authMode = payload[2].u8(),
            cipher = payload[3].u8(),
            groupCipher = payload[4].u8(),
            primaryChannel = payload[5].u8(),
            bssid = bssid,
            ssid = ssid,
        )
    }

    fun isWifiScanComplete(frame: Frame): Boolean =
        frame.command == WIFI_NETWORKS_RESPONSE && frame.payload.contentEquals(byteArrayOf(1))

    fun createWifiCredentialFrame(network: WifiNetwork, password: String): ByteArray {
        val ssidBytes = network.ssid.toByteArray(StandardCharsets.UTF_8)
        require(ssidBytes.isNotEmpty() && ssidBytes.size <= 255) { "The Wi-Fi name must be 1 to 255 UTF-8 bytes." }
        require(network.bssid.size == 6) { "The selected network did not include a valid BSSID." }
        require(network.authMode in 0..255) { "The selected network has an unsupported security mode." }

        val payload = ByteArrayOutputStream()
        payload.writeLengthPrefixed(ssidBytes)
        payload.write(network.bssid)
        payload.write(network.authMode)

        if (!network.isOpen) {
            require(password.isNotEmpty()) { "Enter the Wi-Fi password." }
            val encrypted = encryptPassword(password)
            try {
                payload.writeLengthPrefixed(encrypted.ciphertext)
                payload.write(encrypted.iv)
            } finally {
                encrypted.ciphertext.fill(0)
                encrypted.iv.fill(0)
            }
        }
        return encodeFrame(version = 1, command = WIFI_CONNECT_REQUEST, payload = payload.toByteArray())
    }

    fun wifiConnectStatus(frame: Frame): Int? =
        frame.payload.singleOrNull()?.u8()?.takeIf { frame.command == WIFI_CONNECT_RESPONSE }

    /**
     * Reports only byte positions that match the finite set of known Wi-Fi status codes.
     * This gives diagnostics a safe clue for unexpected two-byte replies without retaining
     * arbitrary response payload bytes.
     */
    fun wifiStatusCodeCandidates(frame: Frame): List<Pair<Int, Int>> {
        if (frame.command != WIFI_CONNECT_RESPONSE || frame.payload.size != 2) return emptyList()
        return frame.payload.indices.mapNotNull { index ->
            frame.payload[index].u8().takeIf { it in KNOWN_WIFI_STATUS_CODES }?.let { code -> index to code }
        }
    }

    fun wifiStatusMessage(status: Int): String = when (status) {
        1 -> "The controller reports that it connected to Wi-Fi."
        3 -> "The password or network authentication was rejected."
        4 -> "The controller could not find that Wi-Fi network."
        5 -> "The Wi-Fi signal is too weak at the controller."
        6 -> "The access point rejected the controller's connection."
        else -> "Wi-Fi setup failed with controller status $status."
    }

    private fun encryptPassword(password: String): EncryptedPassword {
        val iv = ByteArray(AES_BLOCK_BYTES).also(SecureRandom()::nextBytes)
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val key = vendorKey()
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return EncryptedPassword(cipher.doFinal(passwordBytes), iv)
        } finally {
            passwordBytes.fill(0)
            key.fill(0)
        }
    }

    /**
     * The vendor app bundles a fixed compatibility key. XOR encoding keeps the
     * literal out of ordinary source searches; it is not a security boundary.
     */
    private fun vendorKey(): ByteArray = ByteArray(OBFUSCATED_KEY.size) {
        (OBFUSCATED_KEY[it] xor KEY_XOR_MASK).toByte()
    }

    private fun crc8(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEach { byte ->
            crc = crc xor byte.u8()
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor CRC_POLYNOMIAL) and 0xFF
                else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun ByteArrayOutputStream.writeLengthPrefixed(value: ByteArray) {
        require(value.size <= 255) { "A Wi-Fi field is too long for the controller protocol." }
        write(value.size)
        write(value)
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }

    private data class EncryptedPassword(val ciphertext: ByteArray, val iv: ByteArray)

    private const val START = 0xAB.toByte()
    private const val END = 0xAA.toByte()
    private const val FRAME_OVERHEAD = 6
    private const val WIFI_RECORD_PREFIX_BYTES = 13
    private const val WIFI_NETWORKS_RESPONSE = 2
    private const val WIFI_CONNECT_REQUEST = 3
    private const val WIFI_CONNECT_RESPONSE = 4
    private val KNOWN_WIFI_STATUS_CODES = setOf(1, 3, 4, 5, 6)
    private const val AES_BLOCK_BYTES = 16
    private const val CRC_POLYNOMIAL = 0x07
    const val DEFAULT_GATT_WRITE_BYTES = 20
    private const val KEY_XOR_MASK = 0x5A
    private val OBFUSCATED_KEY = intArrayOf(
        0x1E, 0x31, 0x63, 0x69, 0x1A, 0x2A, 0x2A, 0x68,
        0x6A, 0x68, 0x6E, 0x7B, 0x00, 0x23, 0x6C, 0x0E,
        0x2B, 0x62, 0x16, 0x35, 0x0C, 0x1F, 0x6D, 0x6D,
        0x38, 0x12, 0x68, 0x2C, 0x16, 0x6F, 0x3B, 0x17,
    )
}
