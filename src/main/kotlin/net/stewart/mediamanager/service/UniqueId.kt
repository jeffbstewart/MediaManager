package net.stewart.mediamanager.service

/**
 * Identifies an ownable item for photo storage purposes.
 * Implementations provide an alphanumeric storage key and two shard characters
 * for directory bucketing.
 */
interface UniqueId {
    /** Alphanumeric string used in filenames. Must match [a-zA-Z0-9]+. */
    val storageKey: String
    /** First shard character for directory bucketing. */
    val shard1: Char
    /** Second shard character for directory bucketing. */
    val shard2: Char
}

/**
 * UniqueId backed by a UPC barcode. Shards on product code digits (positions 7-8),
 * which vary most across items from the same manufacturer.
 *
 * UPC-A layout: S-MMMMM-PPPPP-C (system, manufacturer, product, check).
 */
class UpcUniqueId(private val upc: String) : UniqueId {
    init {
        require(upc.matches(Regex("[a-zA-Z0-9]+"))) { "UPC must be alphanumeric: $upc" }
        require(upc.length >= 10) { "UPC too short for product code sharding: $upc" }
    }

    override val storageKey: String get() = upc
    override val shard1: Char get() = upc[7]
    override val shard2: Char get() = upc[8]
}
