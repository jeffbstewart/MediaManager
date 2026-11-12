package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.stewart.mediamanager.entity.LiveTvChannel
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI

object HdHomeRunService {
    private val log = LoggerFactory.getLogger(HdHomeRunService::class.java)
    private val gson = Gson()
    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    data class TunerDiscoveryResult(
        val friendlyName: String,
        val modelNumber: String,
        val tunerCount: Int,
        val deviceId: String,
        val baseUrl: String,
        val lineupUrl: String,
        val firmwareVersion: String,
        val ipAddress: String
    )

    data class ChannelLineupEntry(
        val guideNumber: String,
        val guideName: String,
        val url: String,
        val tags: String
    )

    data class SyncResult(val added: Int, val updated: Int, val deleted: Int)

    /**
     * Validates IP format (IPv4 only, no hostnames to prevent SSRF) and
     * fetches discover.json from the HDHomeRun device.
     */
    fun discoverDevice(ip: String): TunerDiscoveryResult? {
        if (!IPV4_REGEX.matches(ip.trim())) {
            log.warn("Invalid IP format: {}", ip)
            return null
        }
        val trimmedIp = ip.trim()
        return try {
            val url = URI("http://$trimmedIp/discover.json").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) {
                log.warn("HDHomeRun at {} returned HTTP {}", trimmedIp, conn.responseCode)
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = gson.fromJson(body, JsonObject::class.java)

            TunerDiscoveryResult(
                friendlyName = json.get("FriendlyName")?.asString ?: "HDHomeRun",
                modelNumber = json.get("ModelNumber")?.asString ?: "",
                tunerCount = json.get("TunerCount")?.asInt ?: 2,
                deviceId = json.get("DeviceID")?.asString ?: "",
                baseUrl = json.get("BaseURL")?.asString ?: "http://$trimmedIp",
                lineupUrl = json.get("LineupURL")?.asString ?: "http://$trimmedIp/lineup.json",
                firmwareVersion = json.get("FirmwareVersion")?.asString
                    ?: json.get("FirmwareName")?.asString ?: "",
                ipAddress = trimmedIp
            )
        } catch (e: Exception) {
            log.warn("Failed to discover HDHomeRun at {}: {}", trimmedIp, e.message)
            null
        }
    }

    /**
     * Fetches the channel lineup from the HDHomeRun device.
     */
    fun fetchLineup(ip: String): List<ChannelLineupEntry>? {
        if (!IPV4_REGEX.matches(ip.trim())) return null
        val trimmedIp = ip.trim()
        return try {
            val url = URI("http://$trimmedIp/lineup.json").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) {
                log.warn("HDHomeRun lineup at {} returned HTTP {}", trimmedIp, conn.responseCode)
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            val jsonArray = gson.fromJson(body, JsonArray::class.java)

            jsonArray.map { elem ->
                val obj = elem.asJsonObject
                ChannelLineupEntry(
                    guideNumber = obj.get("GuideNumber")?.asString ?: "",
                    guideName = obj.get("GuideName")?.asString ?: "",
                    url = obj.get("URL")?.asString ?: "",
                    tags = obj.get("Tags")?.asString ?: ""
                )
            }.filter { it.guideNumber.isNotBlank() && it.url.isNotBlank() }
        } catch (e: Exception) {
            log.warn("Failed to fetch lineup from {}: {}", trimmedIp, e.message)
            null
        }
    }

    /**
     * Syncs channels for a tuner: updates existing, inserts new, deletes absent.
     */
    fun syncChannels(tunerId: Long, ip: String): SyncResult? {
        val lineup = fetchLineup(ip) ?: return null

        val existing = LiveTvChannel.findAll().filter { it.tuner_id == tunerId }
        val existingByGuideNum = existing.associateBy { it.guide_number }
        val lineupGuideNumbers = lineup.map { it.guideNumber }.toSet()

        var added = 0
        var updated = 0
        var deleted = 0

        // Update or insert
        for ((index, entry) in lineup.withIndex()) {
            val channel = existingByGuideNum[entry.guideNumber]
            if (channel != null) {
                var changed = false
                if (channel.guide_name != entry.guideName) { channel.guide_name = entry.guideName; changed = true }
                if (channel.stream_url != entry.url) { channel.stream_url = entry.url; changed = true }
                if (channel.tags != entry.tags) { channel.tags = entry.tags; changed = true }
                if (changed) {
                    channel.save()
                    updated++
                }
            } else {
                LiveTvChannel(
                    tuner_id = tunerId,
                    guide_number = entry.guideNumber,
                    guide_name = entry.guideName,
                    stream_url = entry.url,
                    tags = entry.tags,
                    display_order = index
                ).save()
                added++
            }
        }

        // Delete channels no longer in lineup
        for (channel in existing) {
            if (channel.guide_number !in lineupGuideNumbers) {
                channel.delete()
                deleted++
            }
        }

        log.info("Channel sync for tuner {}: {} added, {} updated, {} deleted", tunerId, added, updated, deleted)
        return SyncResult(added, updated, deleted)
    }
}
