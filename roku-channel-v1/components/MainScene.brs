sub init()
    print "[MM] MainScene: init"
    m.settingsScreen = m.top.findNode("settingsScreen")
    m.homeScreen = m.top.findNode("homeScreen")
    m.detailScreen = m.top.findNode("detailScreen")
    m.episodePicker = m.top.findNode("episodePicker")
    m.videoPlayer = m.top.findNode("videoPlayer")
    m.feedTask = m.top.findNode("feedTask")

    ' Screen stack for Back button navigation
    m.screenStack = []

    ' Observe child screen signals
    m.homeScreen.observeField("selectedItem", "onItemSelected")
    m.detailScreen.observeField("playRequested", "onPlayRequested")
    m.detailScreen.observeField("episodesRequested", "onEpisodesRequested")
    m.episodePicker.observeField("episodeSelected", "onEpisodeSelected")
    m.settingsScreen.observeField("settingsSaved", "onSettingsSaved")
    m.feedTask.observeField("feedContent", "onFeedLoaded")
    m.feedTask.observeField("feedError", "onFeedError")
    m.videoPlayer.observeField("state", "onVideoState")
    m.videoPlayer.observeField("position", "onVideoPosition")

    ' Current video content ID for bookmarks
    m.currentVideoId = ""

    ' Check for saved settings
    reg = CreateObject("roRegistrySection", "MediaManager")
    m.serverUrl = reg.Read("serverUrl")
    m.apiKey = reg.Read("apiKey")

    maskedKey = maskApiKey(m.apiKey)
    print "[MM] MainScene: registry loaded — serverUrl=" ; m.serverUrl ; " apiKey=" ; maskedKey

    if m.serverUrl = "" or m.apiKey = ""
        print "[MM] MainScene: settings incomplete, showing settings screen"
        showScreen(m.settingsScreen)
    else
        print "[MM] MainScene: settings OK, fetching feed"
        fetchFeed()
    end if
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

' ---- Feed Loading ----

sub fetchFeed()
    print "[MM] MainScene: fetchFeed starting"
    m.feedTask.functionName = "doTask"
    m.feedTask.serverUrl = m.serverUrl
    m.feedTask.apiKey = m.apiKey
    m.feedTask.control = "run"

    ' Show home screen with loading state
    showScreen(m.homeScreen)
end sub

sub onFeedLoaded()
    content = m.feedTask.feedContent
    if content <> invalid
        rowCount = content.getChildCount()
        totalItems = 0
        for i = 0 to rowCount - 1
            totalItems = totalItems + content.getChild(i).getChildCount()
        end for
        print "[MM] MainScene: onFeedLoaded — " ; str(rowCount).trim() ; " rows, " ; str(totalItems).trim() ; " total items"
        m.homeScreen.content = content
    else
        print "[MM] MainScene: onFeedLoaded — content is invalid"
    end if
end sub

sub onFeedError()
    errorMsg = m.feedTask.feedError
    if errorMsg <> "" and errorMsg <> invalid
        httpCode = m.feedTask.feedHttpCode
        print "[MM] MainScene: onFeedError — code=" ; str(httpCode).trim() ; " msg=" ; errorMsg

        if httpCode = 401
            ' Auth failed — clear stale apiKey and go straight to re-pair
            print "[MM] MainScene: 401 auth failure — clearing apiKey, navigating to settings"
            reg = CreateObject("roRegistrySection", "MediaManager")
            reg.Delete("apiKey")
            reg.Flush()
            m.apiKey = ""

            ' Clear the empty HomeScreen from the stack before showing settings
            ' so Back from settings exits the app instead of showing a blank screen
            m.screenStack = []
            m.homeScreen.visible = false

            showScreen(m.settingsScreen)
            m.settingsScreen.reauthenticate = true
        else
            showMessage("Feed Error", errorMsg + chr(10) + "Press * to open Settings.")
        end if
    end if
end sub

' ---- Item Selection ----

sub onItemSelected()
    item = m.homeScreen.selectedItem
    if item = invalid then return

    itemType = ""
    if item.hasField("itemType") then itemType = item.itemType
    print "[MM] MainScene: onItemSelected — title=" ; item.title ; " type=" ; itemType
    m.detailScreen.itemContent = item
    showScreen(m.detailScreen)
end sub

' ---- Playback ----

sub onPlayRequested()
    videoContent = m.detailScreen.videoContent
    if videoContent = invalid then return
    print "[MM] MainScene: onPlayRequested — title=" ; videoContent.title
    startPlayback(videoContent)
end sub

sub onEpisodesRequested()
    seriesContent = m.detailScreen.itemContent
    if seriesContent = invalid then return
    print "[MM] MainScene: onEpisodesRequested — title=" ; seriesContent.title
    m.episodePicker.seriesContent = seriesContent
    showScreen(m.episodePicker)
end sub

sub onEpisodeSelected()
    videoContent = m.episodePicker.videoContent
    if videoContent = invalid then return
    print "[MM] MainScene: onEpisodeSelected — title=" ; videoContent.title
    startPlayback(videoContent)
end sub

sub startPlayback(videoContent as object)
    m.currentVideoId = videoContent.id
    m.lastReportedPos = 0
    print "[MM] MainScene: startPlayback — id=" ; m.currentVideoId

    ' Check for local bookmark first, then fall back to server-side position
    bookmarkPos = readBookmark(m.currentVideoId)
    if bookmarkPos <= 30
        ' No local bookmark — check server-side position from feed
        if videoContent.hasField("playbackPosition") and videoContent.playbackPosition <> invalid and videoContent.playbackPosition > 30
            bookmarkPos = videoContent.playbackPosition
            print "[MM] MainScene: using server-side position " ; str(bookmarkPos).trim() ; "s"
        end if
    end if

    if bookmarkPos > 30
        print "[MM] MainScene: bookmark found at " ; str(bookmarkPos).trim() ; "s, showing resume dialog"
        showResumeDialog(videoContent, bookmarkPos)
    else
        print "[MM] MainScene: no bookmark (or <30s), playing from start"
        playVideo(videoContent, 0)
    end if
end sub

sub playVideo(videoContent as object, startPos as integer)
    print "[MM] MainScene: playVideo — title=" ; videoContent.title ; " startPos=" ; str(startPos).trim()

    ' Ensure audio is not muted
    m.videoPlayer.mute = false

    ' Configure subtitles if available
    if videoContent.hasField("subtitleUrl") and videoContent.subtitleUrl <> invalid and videoContent.subtitleUrl <> ""
        print "[MM] MainScene: configuring subtitles — " ; videoContent.subtitleUrl
        videoContent.SubtitleConfig = {
            TrackName: "subtitleTrack1",
            Language: "eng",
            Description: "English"
        }
        videoContent.SubtitleTracks = [{
            TrackName: "subtitleTrack1",
            Language: "eng",
            Description: "English",
            Url: videoContent.subtitleUrl
        }]
        m.videoPlayer.globalCaptionMode = "On"
    else
        m.videoPlayer.globalCaptionMode = "Off"
    end if

    m.videoPlayer.content = videoContent
    m.videoPlayer.visible = true
    m.videoPlayer.setFocus(true)

    if startPos > 0
        m.videoPlayer.seek = startPos
    end if

    m.videoPlayer.control = "play"
end sub

sub stopVideo()
    print "[MM] MainScene: stopVideo"

    ' Report final position to server before stopping
    if m.currentVideoId <> ""
        currentPos = m.videoPlayer.position
        duration = m.videoPlayer.duration
        if currentPos > 0
            reportProgressToServer(m.currentVideoId, currentPos, duration)
        end if
    end if

    m.videoPlayer.control = "stop"
    m.videoPlayer.visible = false

    ' Return focus to top screen
    if m.screenStack.count() > 0
        m.screenStack[m.screenStack.count() - 1].setFocus(true)
    end if
end sub

' ---- Video State Callbacks ----

sub onVideoState()
    state = m.videoPlayer.state
    print "[MM] MainScene: onVideoState — " ; state

    if state = "playing"
        ' Debug: log audio track info
        if m.videoPlayer.hasField("audioTrack")
            print "[MM] MainScene: audioTrack=" ; formatJSON(m.videoPlayer.audioTrack)
        else
            print "[MM] MainScene: audioTrack field not found"
        end if
        if m.videoPlayer.hasField("availableAudioTracks")
            print "[MM] MainScene: availableAudioTracks=" ; formatJSON(m.videoPlayer.availableAudioTracks)
        else
            print "[MM] MainScene: availableAudioTracks field not found"
        end if
        if m.videoPlayer.hasField("mute")
            print "[MM] MainScene: mute=" ; m.videoPlayer.mute.toStr()
        end if
        cnt = m.videoPlayer.content
        if cnt <> invalid
            print "[MM] MainScene: contentUrl=" ; cnt.url
            print "[MM] MainScene: streamFormat=" ; cnt.streamFormat
        end if
    end if

    if state = "finished"
        ' Clear bookmark on completion
        clearBookmark(m.currentVideoId)
        stopVideo()
    else if state = "error"
        print "[MM] MainScene: playback error — errorCode=" ; str(m.videoPlayer.errorCode).trim() ; " errorMsg=" ; m.videoPlayer.errorMsg
        stopVideo()
        showMessage("Playback Error", "Could not play this video.")
    end if
end sub

sub onVideoPosition()
    if m.videoPlayer.state = "playing" and m.currentVideoId <> ""
        currentPos = m.videoPlayer.position
        writeBookmark(m.currentVideoId, int(currentPos))

        ' Report to server every ~60 seconds
        if m.lastReportedPos = invalid then m.lastReportedPos = 0
        elapsed = abs(currentPos - m.lastReportedPos)
        if elapsed >= 60
            m.lastReportedPos = currentPos
            duration = m.videoPlayer.duration
            reportProgressToServer(m.currentVideoId, currentPos, duration)
        end if
    end if
end sub

' ---- Bookmark Management ----

sub writeBookmark(contentId as string, position as integer)
    reg = CreateObject("roRegistrySection", "Bookmarks")
    reg.Write(contentId, str(position).trim())
    reg.Flush()
end sub

function readBookmark(contentId as string) as integer
    reg = CreateObject("roRegistrySection", "Bookmarks")
    savedPos = reg.Read(contentId)
    if savedPos <> "" and savedPos <> invalid
        return savedPos.toInt()
    end if
    return 0
end function

sub clearBookmark(contentId as string)
    print "[MM] MainScene: clearBookmark — id=" ; contentId
    reg = CreateObject("roRegistrySection", "Bookmarks")
    reg.Delete(contentId)
    reg.Flush()
end sub

' ---- Server Progress Sync ----

sub reportProgressToServer(contentId as string, position as dynamic, duration as dynamic)
    if m.serverUrl = "" or m.apiKey = "" then return
    if contentId = "" or contentId = invalid then return

    ' Use ProgressTask to make the HTTP call on a Task thread
    ' (roUrlTransfer cannot be created on the Render thread)
    m.progressTask = m.top.findNode("progressTask")
    m.progressTask.control = "stop"
    m.progressTask.serverUrl = m.serverUrl
    m.progressTask.apiKey = m.apiKey
    m.progressTask.contentId = contentId
    m.progressTask.position = str(position).trim()
    m.progressTask.duration = str(duration).trim()
    m.progressTask.functionName = "doTask"
    m.progressTask.control = "run"
end sub

' ---- Resume Dialog ----

sub showResumeDialog(videoContent as object, bookmarkPos as integer)
    dialog = createObject("roSGNode", "StandardMessageDialog")
    dialog.title = "Resume Playback"

    minutes = int(bookmarkPos / 60)
    seconds = bookmarkPos mod 60
    timeStr = str(minutes).trim() + "m " + str(seconds).trim() + "s"
    dialog.message = ["Resume from " + timeStr + "?"]
    dialog.buttons = ["Resume", "Start Over"]

    dialog.observeField("buttonSelected", "onResumeDialogButton")
    m.resumeVideoContent = videoContent
    m.resumePosition = bookmarkPos
    m.top.dialog = dialog
end sub

sub onResumeDialogButton()
    dialog = m.top.dialog
    if dialog = invalid then return

    buttonIndex = dialog.buttonSelected
    m.top.dialog = invalid

    if buttonIndex = 0
        ' Resume
        print "[MM] MainScene: resume dialog — user chose Resume at " ; str(m.resumePosition).trim() ; "s"
        playVideo(m.resumeVideoContent, m.resumePosition)
    else
        ' Start Over
        print "[MM] MainScene: resume dialog — user chose Start Over"
        clearBookmark(m.currentVideoId)
        playVideo(m.resumeVideoContent, 0)
    end if
end sub

' ---- Settings ----

sub onSettingsSaved()
    reg = CreateObject("roRegistrySection", "MediaManager")
    m.serverUrl = reg.Read("serverUrl")
    m.apiKey = reg.Read("apiKey")

    maskedKey = maskApiKey(m.apiKey)
    print "[MM] MainScene: onSettingsSaved — serverUrl=" ; m.serverUrl ; " apiKey=" ; maskedKey

    ' Remove settings from stack and fetch feed
    hideTopScreen()

    ' Reset feed task for a new run
    m.feedTask.control = "stop"

    if m.screenStack.count() = 0
        fetchFeed()
    else
        ' Re-fetch feed in the background
        m.feedTask.functionName = "doTask"
        m.feedTask.serverUrl = m.serverUrl
        m.feedTask.apiKey = m.apiKey
        m.feedTask.control = "run"
    end if
end sub

' ---- Key Handling ----

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        ' Stop video if playing
        if m.videoPlayer.visible and m.videoPlayer.state <> "none" and m.videoPlayer.state <> "stopped"
            print "[MM] MainScene: back key — stopping video"
            stopVideo()
            return true
        end if

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

    if key = "options"
        ' * button — open settings from anywhere
        if m.settingsScreen.visible = false
            print "[MM] MainScene: options key — opening settings"
            showScreen(m.settingsScreen)
            return true
        end if
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

sub showMessage(title as string, message as string)
    dialog = createObject("roSGNode", "StandardMessageDialog")
    dialog.title = title
    dialog.message = [message]
    dialog.buttons = ["OK"]
    dialog.observeField("buttonSelected", "onMessageDismissed")
    m.top.dialog = dialog
end sub

sub onMessageDismissed()
    m.top.dialog = invalid
end sub
