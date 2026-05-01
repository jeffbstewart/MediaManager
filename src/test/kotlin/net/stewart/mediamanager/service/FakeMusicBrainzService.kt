package net.stewart.mediamanager.service

/**
 * In-memory test double for [MusicBrainzService]. Each lookup is keyed
 * off the input the production code passes in; unscripted inputs return
 * `NotFound` (or empty list, matching the contract of the real service
 * for a miss).
 *
 * Construct with the responses you want, then pass the instance to the
 * collaborator under test. Properties are mutable so tests can adjust
 * the script mid-run when needed.
 */
class FakeMusicBrainzService(
    var byBarcode: Map<String, MusicBrainzResult> = emptyMap(),
    var byReleaseMbid: Map<String, MusicBrainzResult> = emptyMap(),
    var artistReleaseGroups: Map<String, List<ArtistReleaseGroupRef>> = emptyMap(),
    var releaseRecordingCredits: Map<String, List<MusicBrainzRecordingCredit>> = emptyMap(),
    var artistMemberships: Map<String, List<MusicBrainzMembership>> = emptyMap(),
    var byCatalogNumber: Map<Pair<String, String?>, List<String>> = emptyMap(),
    var byIsrc: Map<String, List<String>> = emptyMap(),
    var byArtistAndAlbum: Map<Pair<String, String>, List<String>> = emptyMap(),
) : MusicBrainzService {

    override fun lookupByBarcode(barcode: String): MusicBrainzResult =
        byBarcode[barcode] ?: MusicBrainzResult.NotFound

    override fun lookupByReleaseMbid(releaseMbid: String): MusicBrainzResult =
        byReleaseMbid[releaseMbid] ?: MusicBrainzResult.NotFound

    override fun listArtistReleaseGroups(artistMbid: String, limit: Int): List<ArtistReleaseGroupRef> =
        artistReleaseGroups[artistMbid].orEmpty()

    override fun listReleaseRecordingCredits(releaseMbid: String): List<MusicBrainzRecordingCredit> =
        releaseRecordingCredits[releaseMbid].orEmpty()

    override fun listArtistMemberships(artistMbid: String): List<MusicBrainzMembership> =
        artistMemberships[artistMbid].orEmpty()

    override fun searchByCatalogNumber(catalogNumber: String, label: String?): List<String> =
        byCatalogNumber[catalogNumber to label].orEmpty()

    override fun searchByIsrc(isrc: String): List<String> = byIsrc[isrc].orEmpty()

    override fun searchReleaseByArtistAndAlbum(albumArtist: String, album: String): List<String> =
        byArtistAndAlbum[albumArtist to album].orEmpty()
}
