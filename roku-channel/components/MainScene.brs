sub init()
    print "[MM] MainScene: init"
    m.profilePickerScreen = m.top.findNode("profilePickerScreen")
    m.pairScreen = m.top.findNode("pairScreen")
    m.homeScreen = m.top.findNode("homeScreen")
    m.movieDetailScreen = m.top.findNode("movieDetailScreen")
    m.episodePickerScreen = m.top.findNode("episodePickerScreen")
    m.videoPlayerScreen = m.top.findNode("videoPlayerScreen")
    m.searchResultsScreen = m.top.findNode("searchResultsScreen")
    m.collectionScreen = m.top.findNode("collectionScreen")
    m.tagScreen = m.top.findNode("tagScreen")
    m.actorScreen = m.top.findNode("actorScreen")
    m.pairTask = m.top.findNode("pairTask")
    m.wishlistTask = m.top.findNode("wishlistTask")

    ' Screen stack for Back button navigation
    m.screenStack = []

    ' Avatar color palette for new profiles
    m.avatarColors = [
        "#6366f1", "#ec4899", "#f59e0b", "#10b981",
        "#3b82f6", "#8b5cf6", "#ef4444", "#14b8a6",
        "#f97316", "#06b6d4", "#84cc16", "#e879f9"
    ]

    ' Observe child screen signals
    m.profilePickerScreen.observeField("selectedProfile", "onProfileSelected")
    m.profilePickerScreen.observeField("addProfileRequested", "onAddProfileRequested")
    m.profilePickerScreen.observeField("removeProfileRequested", "onRemoveProfileRequested")
    m.pairScreen.observeField("pairComplete", "onPairComplete")
    m.pairScreen.observeField("cancelRequested", "onPairCancelled")
    m.homeScreen.observeField("switchProfileRequested", "onSwitchProfileRequested")
    m.homeScreen.observeField("removeProfileRequested", "onRemoveProfileFromHome")
    m.homeScreen.observeField("playRequested", "onPlayRequested")
    m.homeScreen.observeField("searchRequested", "onSearchRequested")
    m.movieDetailScreen.observeField("playRequested", "onMovieDetailPlayRequested")
    m.movieDetailScreen.observeField("titleSelected", "onDetailTitleSelected")
    m.movieDetailScreen.observeField("actorSelected", "onDetailActorSelected")
    m.episodePickerScreen.observeField("episodeSelected", "onEpisodeSelected")
    m.episodePickerScreen.observeField("titleSelected", "onDetailTitleSelected")
    m.episodePickerScreen.observeField("actorSelected", "onDetailActorSelected")
    m.movieDetailScreen.observeField("tagSelected", "onDetailTagSelected")
    m.episodePickerScreen.observeField("tagSelected", "onDetailTagSelected")
    m.videoPlayerScreen.observeField("playbackFinished", "onPlaybackFinished")
    m.videoPlayerScreen.observeField("nextEpisodeStarted", "onNextEpisodeStarted")

    ' Search results screen signals
    m.searchResultsScreen.observeField("playRequested", "onSearchPlayRequested")
    m.searchResultsScreen.observeField("episodePickerRequested", "onSearchEpisodePickerRequested")
    m.searchResultsScreen.observeField("collectionSelected", "onCollectionSelected")
    m.searchResultsScreen.observeField("tagSelected", "onTagSelected")
    m.searchResultsScreen.observeField("genreSelected", "onGenreSelected")
    m.searchResultsScreen.observeField("actorSelected", "onActorSelected")

    ' Landing page screen signals
    m.collectionScreen.observeField("playRequested", "onCollectionPlayRequested")
    m.collectionScreen.observeField("episodePickerRequested", "onCollectionEpisodePickerRequested")
    m.tagScreen.observeField("playRequested", "onTagPlayRequested")
    m.tagScreen.observeField("episodePickerRequested", "onTagEpisodePickerRequested")
    m.actorScreen.observeField("playRequested", "onActorPlayRequested")
    m.actorScreen.observeField("episodePickerRequested", "onActorEpisodePickerRequested")
    m.actorScreen.observeField("wishRequested", "onWishRequested")

    ' Wishlist task signals
    m.wishlistTask.observeField("wishResult", "onWishResult")
    m.wishlistTask.observeField("wishError", "onWishError")

    ' Clean up V1 registry if needed
    cleanupV1Registry()

    ' Load profiles and show picker
    profiles = loadProfiles()
    if profiles.count() = 0
        print "[MM] MainScene: no profiles, going directly to PairScreen"
        showScreen(m.pairScreen)
    else
        print "[MM] MainScene: " ; str(profiles.count()).trim() ; " profile(s) found, showing picker"
        m.profilePickerScreen.profiles = profiles
        showScreen(m.profilePickerScreen)
    end if
end sub

' ---- V1 Registry Cleanup ----

sub cleanupV1Registry()
    ' Check if new Profiles section exists
    profilesReg = CreateObject("roRegistrySection", "Profiles")
    countStr = profilesReg.Read("count")

    if countStr <> "" and countStr <> invalid
        ' Profiles section exists, V1 cleanup already done or not needed
        return
    end if

    ' No Profiles data — delete stale V1 keys
    print "[MM] MainScene: cleaning up V1 registry keys"

    v1Reg = CreateObject("roRegistrySection", "MediaManager")
    v1Reg.Delete("serverUrl")
    v1Reg.Delete("apiKey")
    v1Reg.Delete("username")
    v1Reg.Flush()

    bookmarksReg = CreateObject("roRegistrySection", "Bookmarks")
    keys = bookmarksReg.GetKeyList()
    for each key in keys
        bookmarksReg.Delete(key)
    end for
    bookmarksReg.Flush()

    print "[MM] MainScene: V1 registry cleanup complete"
end sub

' ---- Profile Management ----

function loadProfiles() as object
    profiles = []
    reg = CreateObject("roRegistrySection", "Profiles")
    countStr = reg.Read("count")

    if countStr = "" or countStr = invalid
        print "[MM] MainScene: loadProfiles — no profiles in registry"
        return profiles
    end if

    count = val(countStr)
    print "[MM] MainScene: loadProfiles — count=" ; str(count).trim()

    for i = 0 to count - 1
        prefix = "p" + str(i).trim() + "_"
        profile = {
            index: i,
            serverUrl: reg.Read(prefix + "serverUrl"),
            apiKey: reg.Read(prefix + "apiKey"),
            username: reg.Read(prefix + "username"),
            avatarColor: reg.Read(prefix + "avatarColor")
        }
        maskedKey = maskApiKey(profile.apiKey)
        print "[MM] MainScene: loadProfiles — [" ; str(i).trim() ; "] " ; profile.username ; " @ " ; profile.serverUrl ; " key=" ; maskedKey
        profiles.push(profile)
    end for

    return profiles
end function

sub saveProfiles(profiles as object)
    reg = CreateObject("roRegistrySection", "Profiles")

    ' Clear old keys first — delete up to a generous max
    for i = 0 to 19
        prefix = "p" + str(i).trim() + "_"
        reg.Delete(prefix + "serverUrl")
        reg.Delete(prefix + "apiKey")
        reg.Delete(prefix + "username")
        reg.Delete(prefix + "avatarColor")
    end for

    ' Write current profiles
    reg.Write("count", str(profiles.count()).trim())

    for i = 0 to profiles.count() - 1
        prefix = "p" + str(i).trim() + "_"
        reg.Write(prefix + "serverUrl", profiles[i].serverUrl)
        reg.Write(prefix + "apiKey", profiles[i].apiKey)
        reg.Write(prefix + "username", profiles[i].username)
        reg.Write(prefix + "avatarColor", profiles[i].avatarColor)
    end for

    reg.Flush()
    print "[MM] MainScene: saveProfiles — saved " ; str(profiles.count()).trim() ; " profile(s)"
end sub

function addProfile(serverUrl as string, apiKey as string, username as string) as object
    profiles = loadProfiles()

    ' Pick an avatar color (cycle through palette)
    colorIndex = profiles.count() mod m.avatarColors.count()
    avatarColor = m.avatarColors[colorIndex]

    profile = {
        index: profiles.count(),
        serverUrl: serverUrl,
        apiKey: apiKey,
        username: username,
        avatarColor: avatarColor
    }

    profiles.push(profile)
    saveProfiles(profiles)

    print "[MM] MainScene: addProfile — added " ; username ; " (color " ; avatarColor ; ")"
    return profile
end function

sub removeProfile(profileIndex as integer)
    profiles = loadProfiles()

    if profileIndex < 0 or profileIndex >= profiles.count()
        print "[MM] MainScene: removeProfile — index " ; str(profileIndex).trim() ; " out of range"
        return
    end if

    removedName = profiles[profileIndex].username
    profiles.delete(profileIndex)

    ' Re-index
    for i = 0 to profiles.count() - 1
        profiles[i].index = i
    end for

    saveProfiles(profiles)

    ' Adjust lastUsed
    reg = CreateObject("roRegistrySection", "Profiles")
    lastUsedStr = reg.Read("lastUsed")
    if lastUsedStr <> "" and lastUsedStr <> invalid
        lastUsed = val(lastUsedStr)
        if lastUsed >= profiles.count()
            lastUsed = profiles.count() - 1
            if lastUsed < 0 then lastUsed = 0
            reg.Write("lastUsed", str(lastUsed).trim())
            reg.Flush()
        end if
    end if

    print "[MM] MainScene: removeProfile — removed " ; removedName ; " (index " ; str(profileIndex).trim() ; ")"
end sub

' ---- Screen Stack Navigation ----

sub showScreen(screen as object)
    ' Hide current top screen
    if m.screenStack.count() > 0
        m.screenStack[m.screenStack.count() - 1].visible = false
    end if

    screen.visible = true
    screen.setFocus(true)
    m.screenStack.push(screen)
    print "[MM] MainScene: showScreen — stack depth=" ; str(m.screenStack.count()).trim()
end sub

sub hideTopScreen()
    if m.screenStack.count() > 0
        topScreen = m.screenStack.pop()
        topScreen.visible = false
    end if

    ' Show and focus previous screen
    if m.screenStack.count() > 0
        prevScreen = m.screenStack[m.screenStack.count() - 1]
        prevScreen.visible = true
        prevScreen.setFocus(true)
    end if
    print "[MM] MainScene: hideTopScreen — stack depth=" ; str(m.screenStack.count()).trim()
end sub

' ---- Event Handlers ----

sub onProfileSelected()
    profile = m.profilePickerScreen.selectedProfile
    if profile = invalid then return

    print "[MM] MainScene: onProfileSelected — " ; profile.username ; " @ " ; profile.serverUrl

    ' Save lastUsed index
    reg = CreateObject("roRegistrySection", "Profiles")
    reg.Write("lastUsed", str(profile.index).trim())
    reg.Flush()

    ' Show HomeScreen with profile data
    m.homeScreen.profileContent = profile
    showScreen(m.homeScreen)
end sub

sub onAddProfileRequested()
    print "[MM] MainScene: onAddProfileRequested — showing PairScreen"
    showScreen(m.pairScreen)
end sub

sub onRemoveProfileRequested()
    profile = m.profilePickerScreen.removeProfileRequested
    if profile = invalid then return

    profileIndex = profile.index
    if profileIndex = invalid then return

    print "[MM] MainScene: onRemoveProfileRequested — removing index " ; str(profileIndex).trim()
    removeProfile(profileIndex)

    ' Reload profiles and update picker
    profiles = loadProfiles()
    m.profilePickerScreen.profiles = profiles

    if profiles.count() = 0
        print "[MM] MainScene: all profiles removed, showing PairScreen"
        ' Clear stack and go to PairScreen
        m.screenStack = []
        m.profilePickerScreen.visible = false
        showScreen(m.pairScreen)
    end if
end sub

sub onPairComplete()
    result = m.pairScreen.pairComplete
    if result = invalid then return

    print "[MM] MainScene: onPairComplete — " ; result.username ; " @ " ; result.serverUrl

    ' Add the new profile
    profile = addProfile(result.serverUrl, result.apiKey, result.username)

    ' Pop PairScreen
    hideTopScreen()

    ' Reload profiles into picker
    profiles = loadProfiles()

    ' Check if picker is on top of stack
    pickerOnTop = false
    if m.screenStack.count() > 0
        topScreen = m.screenStack[m.screenStack.count() - 1]
        if topScreen.isSameNode(m.profilePickerScreen)
            pickerOnTop = true
        end if
    end if

    if not pickerOnTop
        ' Picker wasn't on stack (first profile from fresh launch) — push it
        m.profilePickerScreen.profiles = profiles
        showScreen(m.profilePickerScreen)
    else
        m.profilePickerScreen.profiles = profiles
    end if

    ' Save lastUsed and auto-select the new profile
    reg = CreateObject("roRegistrySection", "Profiles")
    reg.Write("lastUsed", str(profile.index).trim())
    reg.Flush()

    ' Go directly to HomeScreen
    m.homeScreen.profileContent = profile
    showScreen(m.homeScreen)
end sub

sub onPairCancelled()
    print "[MM] MainScene: onPairCancelled — returning to profile picker"
    hideTopScreen()
end sub

sub onSwitchProfileRequested()
    print "[MM] MainScene: onSwitchProfileRequested — returning to profile picker"
    ' Pop HomeScreen
    hideTopScreen()

    ' Refresh profile data in picker
    profiles = loadProfiles()
    m.profilePickerScreen.profiles = profiles
end sub

sub onRemoveProfileFromHome()
    profile = m.top.findNode("homeScreen").profileContent
    if profile = invalid then return

    profileIndex = profile.index
    if profileIndex = invalid then return

    print "[MM] MainScene: onRemoveProfileFromHome — removing index " ; str(profileIndex).trim()
    removeProfile(profileIndex)

    ' Pop HomeScreen
    hideTopScreen()

    ' Reload profiles and update picker
    profiles = loadProfiles()
    m.profilePickerScreen.profiles = profiles

    if profiles.count() = 0
        print "[MM] MainScene: all profiles removed, showing PairScreen"
        m.screenStack = []
        m.profilePickerScreen.visible = false
        showScreen(m.pairScreen)
    end if
end sub

' ---- Video Playback ----

sub onPlayRequested()
    playData = m.homeScreen.playRequested
    if playData = invalid then return

    print "[MM] MainScene: play requested — " ; playData.name ; " (mediaType=" ; playData.mediaType ; ")"

    serverUrl = m.homeScreen.profileContent.serverUrl
    apiKey = m.homeScreen.profileContent.apiKey

    if playData.mediaType = "TV"
        ' TV series: show episode picker
        print "[MM] MainScene: routing to episode picker for TV title"
        m.episodePickerScreen.serverUrl = serverUrl
        m.episodePickerScreen.apiKey = apiKey
        m.episodePickerScreen.titleData = playData
        showScreen(m.episodePickerScreen)
    else
        ' Movie: show detail screen
        print "[MM] MainScene: routing to movie detail"
        m.movieDetailScreen.serverUrl = serverUrl
        m.movieDetailScreen.apiKey = apiKey
        m.movieDetailScreen.titleData = playData
        showScreen(m.movieDetailScreen)
    end if
end sub

sub onEpisodeSelected()
    playData = m.episodePickerScreen.episodeSelected
    if playData = invalid then return

    print "[MM] MainScene: episode selected — " ; playData.name ; " (transcode " ; str(playData.transcodeId).trim() ; ")"

    ' Add server credentials
    playData.serverUrl = m.homeScreen.profileContent.serverUrl
    playData.apiKey = m.homeScreen.profileContent.apiKey

    m.videoPlayerScreen.playContent = playData
    showScreen(m.videoPlayerScreen)
end sub

sub onPlaybackFinished()
    print "[MM] MainScene: playback finished, returning"
    hideTopScreen()

    ' Refresh home feed to update resume positions
    m.homeScreen.profileContent = m.homeScreen.profileContent
end sub

sub onNextEpisodeStarted()
    content = m.videoPlayerScreen.nextEpisodeStarted
    if content = invalid then return
    print "[MM] MainScene: auto-advancing to next episode — " ; content.name
end sub

sub onMovieDetailPlayRequested()
    playData = m.movieDetailScreen.playRequested
    if playData = invalid then return

    print "[MM] MainScene: movie detail play — " ; playData.name

    ' playData is the full title detail from the server — build video play data
    videoData = {
        name: playData.name,
        serverUrl: m.homeScreen.profileContent.serverUrl,
        apiKey: m.homeScreen.profileContent.apiKey,
        transcodeId: playData.transcodeId,
        streamUrl: playData.streamUrl,
        subtitleUrl: playData.subtitleUrl,
        bifUrl: playData.bifUrl,
        quality: playData.quality,
        resumePosition: playData.resumePosition,
        mediaType: "MOVIE"
    }

    m.videoPlayerScreen.playContent = videoData
    showScreen(m.videoPlayerScreen)
end sub

' ---- Detail Screen Navigation (Cast & Similar from Movie/TV Detail) ----

sub onDetailTitleSelected()
    ' Similar title selected from MovieDetailScreen or EpisodePickerScreen
    ' Determine which screen fired it
    st = invalid
    if m.movieDetailScreen.titleSelected <> invalid
        st = m.movieDetailScreen.titleSelected
    end if
    if st = invalid and m.episodePickerScreen.titleSelected <> invalid
        st = m.episodePickerScreen.titleSelected
    end if
    if st = invalid then return

    serverUrl = m.homeScreen.profileContent.serverUrl
    apiKey = m.homeScreen.profileContent.apiKey

    print "[MM] MainScene: similar title selected — " ; st.name ; " (mediaType=" ; st.mediaType ; ")"

    if st.mediaType = "TV"
        m.episodePickerScreen.serverUrl = serverUrl
        m.episodePickerScreen.apiKey = apiKey
        m.episodePickerScreen.titleData = st
        showScreen(m.episodePickerScreen)
    else
        m.movieDetailScreen.serverUrl = serverUrl
        m.movieDetailScreen.apiKey = apiKey
        m.movieDetailScreen.titleData = st
        showScreen(m.movieDetailScreen)
    end if
end sub

sub onDetailTagSelected()
    ' Tag selected from MovieDetailScreen or EpisodePickerScreen
    tagData = invalid
    if m.movieDetailScreen.tagSelected <> invalid
        tagData = m.movieDetailScreen.tagSelected
    end if
    if tagData = invalid and m.episodePickerScreen.tagSelected <> invalid
        tagData = m.episodePickerScreen.tagSelected
    end if
    if tagData = invalid then return

    print "[MM] MainScene: tag selected from detail — " ; tagData.name

    m.tagScreen.serverUrl = m.homeScreen.profileContent.serverUrl
    m.tagScreen.apiKey = m.homeScreen.profileContent.apiKey
    m.tagScreen.detailType = "tag"
    m.tagScreen.tagData = tagData
    showScreen(m.tagScreen)
end sub

sub onDetailActorSelected()
    ' Cast member selected from MovieDetailScreen or EpisodePickerScreen
    actorData = invalid
    if m.movieDetailScreen.actorSelected <> invalid
        actorData = m.movieDetailScreen.actorSelected
    end if
    if actorData = invalid and m.episodePickerScreen.actorSelected <> invalid
        actorData = m.episodePickerScreen.actorSelected
    end if
    if actorData = invalid then return

    print "[MM] MainScene: actor selected from detail — " ; actorData.name

    m.actorScreen.serverUrl = m.homeScreen.profileContent.serverUrl
    m.actorScreen.apiKey = m.homeScreen.profileContent.apiKey
    m.actorScreen.actorData = actorData
    showScreen(m.actorScreen)
end sub

' ---- Search ----

sub onSearchRequested()
    query = m.homeScreen.searchRequested
    if query = invalid or query = "" then return

    print "[MM] MainScene: search requested — '" ; query ; "'"

    ' Pass credentials and query to search results screen
    m.searchResultsScreen.serverUrl = m.homeScreen.profileContent.serverUrl
    m.searchResultsScreen.apiKey = m.homeScreen.profileContent.apiKey
    m.searchResultsScreen.searchQuery = query
    showScreen(m.searchResultsScreen)
end sub

' ---- Search Results Navigation ----

sub onSearchPlayRequested()
    playData = m.searchResultsScreen.playRequested
    if playData = invalid then return

    print "[MM] MainScene: search play requested — " ; playData.name

    serverUrl = m.homeScreen.profileContent.serverUrl
    apiKey = m.homeScreen.profileContent.apiKey

    ' Route to movie detail screen instead of direct play
    m.movieDetailScreen.serverUrl = serverUrl
    m.movieDetailScreen.apiKey = apiKey
    m.movieDetailScreen.titleData = playData
    showScreen(m.movieDetailScreen)
end sub

sub onSearchEpisodePickerRequested()
    titleData = m.searchResultsScreen.episodePickerRequested
    if titleData = invalid then return

    print "[MM] MainScene: search episode picker requested — " ; titleData.name

    serverUrl = m.homeScreen.profileContent.serverUrl
    apiKey = m.homeScreen.profileContent.apiKey

    m.episodePickerScreen.serverUrl = serverUrl
    m.episodePickerScreen.apiKey = apiKey
    m.episodePickerScreen.titleData = titleData
    showScreen(m.episodePickerScreen)
end sub

sub onCollectionSelected()
    collData = m.searchResultsScreen.collectionSelected
    if collData = invalid then return

    print "[MM] MainScene: collection selected — " ; collData.name

    m.collectionScreen.serverUrl = m.homeScreen.profileContent.serverUrl
    m.collectionScreen.apiKey = m.homeScreen.profileContent.apiKey
    m.collectionScreen.collectionData = collData
    showScreen(m.collectionScreen)
end sub

sub onTagSelected()
    tagData = m.searchResultsScreen.tagSelected
    if tagData = invalid then return

    print "[MM] MainScene: tag selected — " ; tagData.name

    m.tagScreen.serverUrl = m.homeScreen.profileContent.serverUrl
    m.tagScreen.apiKey = m.homeScreen.profileContent.apiKey
    m.tagScreen.detailType = "tag"
    m.tagScreen.tagData = tagData
    showScreen(m.tagScreen)
end sub

sub onGenreSelected()
    genreData = m.searchResultsScreen.genreSelected
    if genreData = invalid then return

    print "[MM] MainScene: genre selected — " ; genreData.name

    ' Reuse TagScreen for genres
    m.tagScreen.serverUrl = m.homeScreen.profileContent.serverUrl
    m.tagScreen.apiKey = m.homeScreen.profileContent.apiKey
    m.tagScreen.detailType = "genre"
    m.tagScreen.tagData = genreData
    showScreen(m.tagScreen)
end sub

sub onActorSelected()
    actorData = m.searchResultsScreen.actorSelected
    if actorData = invalid then return

    print "[MM] MainScene: actor selected — " ; actorData.name

    m.actorScreen.serverUrl = m.homeScreen.profileContent.serverUrl
    m.actorScreen.apiKey = m.homeScreen.profileContent.apiKey
    m.actorScreen.actorData = actorData
    showScreen(m.actorScreen)
end sub

' ---- Landing Page Navigation (Collection, Tag, Actor -> Play/Episode Picker) ----

sub onCollectionPlayRequested()
    handleLandingPlay(m.collectionScreen.playRequested)
end sub

sub onCollectionEpisodePickerRequested()
    handleLandingEpisodePicker(m.collectionScreen.episodePickerRequested)
end sub

sub onTagPlayRequested()
    handleLandingPlay(m.tagScreen.playRequested)
end sub

sub onTagEpisodePickerRequested()
    handleLandingEpisodePicker(m.tagScreen.episodePickerRequested)
end sub

sub onActorPlayRequested()
    handleLandingPlay(m.actorScreen.playRequested)
end sub

sub onActorEpisodePickerRequested()
    handleLandingEpisodePicker(m.actorScreen.episodePickerRequested)
end sub

sub handleLandingPlay(playData as object)
    if playData = invalid then return

    print "[MM] MainScene: landing page play requested — " ; playData.name

    serverUrl = m.homeScreen.profileContent.serverUrl
    apiKey = m.homeScreen.profileContent.apiKey

    ' Route to movie detail screen instead of direct play
    m.movieDetailScreen.serverUrl = serverUrl
    m.movieDetailScreen.apiKey = apiKey
    m.movieDetailScreen.titleData = playData
    showScreen(m.movieDetailScreen)
end sub

sub handleLandingEpisodePicker(titleData as object)
    if titleData = invalid then return

    print "[MM] MainScene: landing page episode picker requested — " ; titleData.name

    serverUrl = m.homeScreen.profileContent.serverUrl
    apiKey = m.homeScreen.profileContent.apiKey

    m.episodePickerScreen.serverUrl = serverUrl
    m.episodePickerScreen.apiKey = apiKey
    m.episodePickerScreen.titleData = titleData
    showScreen(m.episodePickerScreen)
end sub

' ---- Wishlist ----

sub onWishRequested()
    wishData = m.actorScreen.wishRequested
    if wishData = invalid then return

    print "[MM] MainScene: wish requested for " ; wishData.name

    serverUrl = m.homeScreen.profileContent.serverUrl
    apiKey = m.homeScreen.profileContent.apiKey

    ' Build the POST URL and body
    wishUrl = serverUrl + "/roku/wishlist/add?key=" + apiKey

    ' Build JSON body from actor detail item data
    bodyObj = {
        tmdb_id: 0,
        media_type: "",
        title: ""
    }

    if wishData.tmdbId <> invalid then bodyObj.tmdb_id = wishData.tmdbId
    if wishData.name <> invalid then bodyObj.title = wishData.name
    if wishData.mediaType <> invalid then bodyObj.media_type = wishData.mediaType
    if wishData.posterPath <> invalid then bodyObj.poster_path = wishData.posterPath
    if wishData.year <> invalid then bodyObj.release_year = wishData.year

    body = formatJSON(bodyObj)

    m.wishlistTask.control = "stop"
    m.wishlistTask.wishUrl = wishUrl
    m.wishlistTask.wishBody = body
    m.wishlistTask.functionName = "doPost"
    m.wishlistTask.control = "run"
end sub

sub onWishResult()
    result = m.wishlistTask.wishResult
    if result = invalid then return

    if result.success = true
        print "[MM] MainScene: wish added successfully"
    else
        reason = ""
        if result.reason <> invalid then reason = result.reason
        print "[MM] MainScene: wish failed — " ; reason
    end if
end sub

sub onWishError()
    errorMsg = m.wishlistTask.wishError
    if errorMsg = invalid then return
    print "[MM] MainScene: wish error — " ; errorMsg
end sub

' ---- Voice Search ----

sub onVoiceSearchQuery()
    query = m.top.voiceSearchQuery
    if query = invalid or query = "" then return

    print "[MM] MainScene: voice search query received: " ; query

    ' Forward to HomeScreen if it's on the stack
    m.homeScreen.searchQuery = query
end sub

' ---- Key Handling ----

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        ' Close dialog if open
        if m.top.dialog <> invalid
            print "[MM] MainScene: back key — closing dialog"
            m.top.dialog = invalid
            return true
        end if

        ' Pop screen stack
        if m.screenStack.count() > 1
            print "[MM] MainScene: back key — popping screen stack"
            hideTopScreen()
            return true
        end if

        ' Last screen — let Roku handle exit
        print "[MM] MainScene: back key — last screen, allowing exit"
        return false
    end if

    return false
end function

' ---- Utility ----

function maskApiKey(key as string) as string
    if key = invalid or key = "" then return "(not set)"
    if len(key) > 8
        return left(key, 8) + "..."
    end if
    return key
end function
