function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] VideoPlayerScreen: init"
    m.videoPlayer = m.top.findNode("videoPlayer")
    m.progressTask = m.top.findNode("progressTask")
    m.skipSegmentTask = m.top.findNode("skipSegmentTask")

    ' Create skip intro overlay as a child of the Video node
    ' (children of Video render on top and receive key events through its focus chain)
    m.skipIntroOverlay = createObject("roSGNode", "Group")
    m.skipIntroOverlay.translation = [1400, 880]
    m.skipIntroOverlay.visible = false
    bg = createObject("roSGNode", "Rectangle")
    bg.width = 440
    bg.height = 70
    bg.color = "#000000"
    bg.opacity = 0.75
    m.skipIntroOverlay.appendChild(bg)
    accent = createObject("roSGNode", "Rectangle")
    accent.width = 4
    accent.height = 70
    accent.color = "#00cccc"
    m.skipIntroOverlay.appendChild(accent)
    lbl = createObject("roSGNode", "Label")
    lbl.translation = [24, 14]
    lbl.text = "Press UP to Skip Intro"
    lbl.font = "font:MediumBoldSystemFont"
    lbl.color = "#00cccc"
    lbl.width = 400
    m.skipIntroOverlay.appendChild(lbl)
    m.videoPlayer.appendChild(m.skipIntroOverlay)

    ' Create credits overlay (same style, different text — updated in onCreditsSegmentLoaded)
    m.creditsOverlay = createObject("roSGNode", "Group")
    m.creditsOverlay.translation = [1400, 880]
    m.creditsOverlay.visible = false
    creditsBg = createObject("roSGNode", "Rectangle")
    creditsBg.width = 440
    creditsBg.height = 70
    creditsBg.color = "#000000"
    creditsBg.opacity = 0.75
    m.creditsOverlay.appendChild(creditsBg)
    creditsAccent = createObject("roSGNode", "Rectangle")
    creditsAccent.width = 4
    creditsAccent.height = 70
    creditsAccent.color = "#00cccc"
    m.creditsOverlay.appendChild(creditsAccent)
    m.creditsLabel = createObject("roSGNode", "Label")
    m.creditsLabel.translation = [24, 14]
    m.creditsLabel.text = "Press UP for Next Episode"
    m.creditsLabel.font = "font:MediumBoldSystemFont"
    m.creditsLabel.color = "#00cccc"
    m.creditsLabel.width = 400
    m.creditsOverlay.appendChild(m.creditsLabel)
    m.videoPlayer.appendChild(m.creditsOverlay)

    m.videoPlayer.observeField("state", "onVideoState")
    m.videoPlayer.observeField("position", "onVideoPosition")
    m.skipSegmentTask.observeField("skipSegments", "onSkipSegmentsLoaded")
    m.skipSegmentTask.observeField("creditsSegment", "onCreditsSegmentLoaded")

    m.serverUrl = ""
    m.apiKey = ""
    m.transcodeId = ""
    m.contentTitle = ""
    m.subtitleUrl = ""
    m.bifUrl = ""
    m.ccEnabled = true
    m.lastReportTime = 0
    m.currentPosition = 0
    m.currentDuration = 0
    m.resumePosition = 0
    m.exiting = false

    ' Skip intro state
    m.skipIntroStart = -1
    m.skipIntroEnd = -1
    m.skipIntroVisible = false

    ' Credits state
    m.skipCreditsStart = -1
    m.skipCreditsEnd = -1
    m.skipCreditsVisible = false

    ' Episode playlist context for auto-advance
    m.showName = ""
    m.playlistSeasons = invalid
    m.playlistSeasonIndex = 0
    m.playlistEpisodeIndex = 0
end sub

' ---- Playback Setup ----

sub onPlayContent()
    content = m.top.playContent
    if content = invalid then return

    m.serverUrl = content.serverUrl
    m.apiKey = content.apiKey
    m.transcodeId = str(content.transcodeId).trim()
    m.contentTitle = content.name
    m.subtitleUrl = ""
    m.bifUrl = ""
    m.resumePosition = 0

    ' Use subtitle URL from play data if provided
    if content.subtitleUrl <> invalid and content.subtitleUrl <> ""
        m.subtitleUrl = content.subtitleUrl
    end if
    ' Use BIF URL from play data if provided (Roku trick play thumbnails)
    if content.bifUrl <> invalid and content.bifUrl <> ""
        m.bifUrl = content.bifUrl
    end if
    if content.resumePosition <> invalid
        m.resumePosition = content.resumePosition
    end if

    ' Store episode playlist context for auto-advance
    m.showName = ""
    m.playlistSeasons = invalid
    m.playlistSeasonIndex = 0
    m.playlistEpisodeIndex = 0
    if content.showName <> invalid
        m.showName = content.showName
    end if
    if content.seasonsData <> invalid
        m.playlistSeasons = content.seasonsData
    end if
    if content.seasonIndex <> invalid
        m.playlistSeasonIndex = content.seasonIndex
    end if
    if content.episodeIndex <> invalid
        m.playlistEpisodeIndex = content.episodeIndex
    end if

    print "[MM " ; mmts() ; "] VideoPlayerScreen: play request — " ; m.contentTitle ; " (transcode " ; m.transcodeId ; ")"

    ' Reset state
    m.exiting = false
    m.lastReportTime = 0
    m.currentPosition = 0
    m.currentDuration = 0

    ' Check for resume position
    if m.resumePosition > 30
        showResumeDialog()
    else
        startPlayback(0)
    end if
end sub

sub showResumeDialog()
    minutes = int(m.resumePosition / 60)
    seconds = m.resumePosition mod 60

    timeStr = str(minutes).trim() + "m" + str(seconds).trim() + "s"
    print "[MM " ; mmts() ; "] VideoPlayerScreen: resume available at " ; timeStr

    dialog = createObject("roSGNode", "StandardMessageDialog")
    dialog.title = "Resume Playback"
    dialog.message = ["Resume from " + timeStr + "?"]
    dialog.buttons = ["Resume", "Start Over"]
    dialog.observeField("buttonSelected", "onResumeDialogButton")
    m.top.getScene().dialog = dialog
end sub

sub onResumeDialogButton()
    dialog = m.top.getScene().dialog
    if dialog = invalid then return

    buttonIndex = dialog.buttonSelected
    m.top.getScene().dialog = invalid

    if buttonIndex = 0
        ' Resume
        print "[MM " ; mmts() ; "] VideoPlayerScreen: resuming from " ; str(m.resumePosition).trim()
        startPlayback(m.resumePosition)
    else
        ' Start over
        print "[MM " ; mmts() ; "] VideoPlayerScreen: starting from beginning"
        startPlayback(0)
    end if
end sub

sub startPlayback(startPos as integer)
    streamUrl = m.serverUrl + "/stream/" + m.transcodeId + "?key=" + m.apiKey
    print "[MM " ; mmts() ; "] VideoPlayerScreen: stream URL = " ; streamUrl

    videoContent = createObject("roSGNode", "ContentNode")
    videoContent.url = streamUrl
    videoContent.title = m.contentTitle
    videoContent.streamFormat = "mp4"

    ' Configure subtitles only if the play data indicated they exist
    if m.subtitleUrl <> ""
        configureSubtitles(videoContent, m.subtitleUrl)
    end if

    ' Configure BIF trick play thumbnails if available
    if m.bifUrl <> ""
        print "[MM " ; mmts() ; "] VideoPlayerScreen: BIF trick play — " ; m.bifUrl
        videoContent.hdBifUrl = m.bifUrl
        videoContent.sdBifUrl = m.bifUrl
    end if

    ' Set caption mode BEFORE content assignment
    if m.subtitleUrl <> "" and m.ccEnabled
        m.videoPlayer.globalCaptionMode = "On"
    else
        m.videoPlayer.globalCaptionMode = "Off"
    end if

    m.videoPlayer.content = videoContent

    ' Seek to resume position if needed
    if startPos > 0
        m.videoPlayer.seek = startPos
    end if

    m.videoPlayer.control = "play"
    m.videoPlayer.setFocus(true)

    ' Fetch skip segments in background
    m.skipIntroStart = -1
    m.skipIntroEnd = -1
    m.skipIntroVisible = false
    m.skipIntroOverlay.visible = false
    m.skipCreditsStart = -1
    m.skipCreditsEnd = -1
    m.skipCreditsVisible = false
    m.creditsOverlay.visible = false
    chaptersUrl = m.serverUrl + "/stream/" + m.transcodeId + "/chapters.json?key=" + m.apiKey
    print "[MM " ; mmts() ; "] VideoPlayerScreen: fetching skip data from " ; chaptersUrl
    m.skipSegmentTask.control = "stop"
    m.skipSegmentTask.chaptersUrl = chaptersUrl
    m.skipSegmentTask.functionName = "fetchSkipSegments"
    m.skipSegmentTask.control = "run"
    print "[MM " ; mmts() ; "] VideoPlayerScreen: skip task started"

    print "[MM " ; mmts() ; "] VideoPlayerScreen: playback started"
end sub

' ---- Subtitle Configuration ----

sub configureSubtitles(videoContent as object, subtitleUrl as string)
    print "[MM " ; mmts() ; "] VideoPlayerScreen: subtitles — " ; subtitleUrl

    ' TrackName IS the URL — it serves as both identifier and subtitle file location
    videoContent.SubtitleConfig = {
        TrackName: subtitleUrl,
        Language: "eng",
        Description: "English"
    }
    videoContent.SubtitleTracks = [{
        TrackName: subtitleUrl,
        Language: "eng",
        Description: "English"
    }]
end sub

sub toggleCC()
    m.ccEnabled = not m.ccEnabled
    if m.ccEnabled
        m.videoPlayer.globalCaptionMode = "On"
        print "[MM " ; mmts() ; "] VideoPlayerScreen: CC turned on"
    else
        m.videoPlayer.globalCaptionMode = "Off"
        print "[MM " ; mmts() ; "] VideoPlayerScreen: CC turned off"
    end if
end sub

' ---- Skip Intro (stubs) ----

sub onSkipSegmentsLoaded()
    seg = m.skipSegmentTask.skipSegments
    if seg = invalid then return
    m.skipIntroStart = seg.startSeconds
    m.skipIntroEnd = seg.endSeconds
    print "[MM " ; mmts() ; "] VideoPlayerScreen: skip intro segment loaded"
end sub

sub checkSkipIntro()
    if m.skipIntroStart < 0 then return
    shouldShow = false
    if m.currentPosition >= m.skipIntroStart
        if m.currentPosition < m.skipIntroEnd
            shouldShow = true
        end if
    end if
    if shouldShow
        if m.skipIntroVisible = false
            m.skipIntroOverlay.visible = true
            m.skipIntroVisible = true
            print "[MM " ; mmts() ; "] VideoPlayerScreen: showing Skip Intro button"
        end if
    else
        if m.skipIntroVisible = true
            m.skipIntroOverlay.visible = false
            m.skipIntroVisible = false
            print "[MM " ; mmts() ; "] VideoPlayerScreen: hiding Skip Intro button"
        end if
    end if
end sub

sub doSkipIntro()
    if m.skipIntroEnd > 0
        print "[MM " ; mmts() ; "] VideoPlayerScreen: skipping intro"
        m.videoPlayer.seek = int(m.skipIntroEnd)
        m.skipIntroOverlay.visible = false
        m.skipIntroVisible = false
        m.skipIntroStart = -1
        m.skipIntroEnd = -1
    end if
end sub

' ---- Credits / Next Episode ----

sub onCreditsSegmentLoaded()
    seg = m.skipSegmentTask.creditsSegment
    if seg = invalid then return
    m.skipCreditsStart = seg.startSeconds
    m.skipCreditsEnd = seg.endSeconds

    ' Set overlay text based on whether a next episode exists
    nextEp = getNextEpisode()
    if nextEp <> invalid
        m.creditsLabel.text = "Press UP for Next Episode"
    else
        m.creditsLabel.text = "Press UP to Exit"
    end if
    print "[MM " ; mmts() ; "] VideoPlayerScreen: credits segment loaded (" ; m.creditsLabel.text ; ")"
end sub

sub checkSkipCredits()
    if m.skipCreditsStart < 0 then return
    shouldShow = false
    if m.currentPosition >= m.skipCreditsStart
        if m.currentPosition < m.skipCreditsEnd
            shouldShow = true
        end if
    end if
    if shouldShow
        if m.skipCreditsVisible = false
            ' Hide skip intro overlay if it's showing (shouldn't overlap)
            m.skipIntroOverlay.visible = false
            m.skipIntroVisible = false
            m.creditsOverlay.visible = true
            m.skipCreditsVisible = true
            print "[MM " ; mmts() ; "] VideoPlayerScreen: showing credits overlay"
        end if
    else
        if m.skipCreditsVisible = true
            m.creditsOverlay.visible = false
            m.skipCreditsVisible = false
            print "[MM " ; mmts() ; "] VideoPlayerScreen: hiding credits overlay"
        end if
    end if
end sub

sub doSkipCredits()
    print "[MM " ; mmts() ; "] VideoPlayerScreen: UP pressed during credits"
    m.creditsOverlay.visible = false
    m.skipCreditsVisible = false
    m.skipCreditsStart = -1
    m.skipCreditsEnd = -1

    nextEp = getNextEpisode()
    if nextEp <> invalid
        m.exiting = true
        reportProgress()
        m.videoPlayer.control = "stop"
        playNextEpisode(nextEp)
    else
        ' No next episode — exit playback
        stopPlayback()
        m.top.playbackFinished = true
    end if
end sub

' ---- Video State / Position ----

sub onVideoState()
    state = m.videoPlayer.state
    ' Only log significant state changes (not buffering/playing churn)
    if state <> "buffering" and state <> "playing" and state <> "paused"
        print "[MM " ; mmts() ; "] VideoPlayerScreen: video state → " ; state
    end if

    if state = "stopped" or state = "finished" or state = "error"
        ' Guard against double-fire (back key sets playbackFinished, then stop state fires again)
        if m.exiting then return

        ' Report final position before exiting
        reportProgress()

        if state = "error"
            print "[MM " ; mmts() ; "] VideoPlayerScreen: playback error — " ; m.videoPlayer.errorMsg
        end if

        if state = "finished"
            ' Try auto-advance to next episode
            nextEp = getNextEpisode()
            if nextEp <> invalid
                m.exiting = true ' Guard against spurious state events during transition
                playNextEpisode(nextEp)
                return
            end if
            print "[MM " ; mmts() ; "] VideoPlayerScreen: playback finished (last episode)"
        end if

        m.exiting = true
        m.videoPlayer.control = "stop"
        m.top.playbackFinished = true
    end if
end sub

sub onVideoPosition()
    m.currentPosition = m.videoPlayer.position
    m.currentDuration = m.videoPlayer.duration

    ' Check skip intro / credits visibility
    checkSkipIntro()
    checkSkipCredits()

    ' Report progress every 60 seconds
    now = createObject("roDateTime")
    nowSeconds = now.AsSeconds()

    if m.lastReportTime = 0
        m.lastReportTime = nowSeconds
    end if

    elapsed = nowSeconds - m.lastReportTime
    if elapsed >= 60
        reportProgress()
        m.lastReportTime = nowSeconds
    end if
end sub

' ---- Progress Reporting ----

sub reportProgress()
    if m.transcodeId = "" or m.serverUrl = "" or m.apiKey = "" then return
    if m.currentPosition <= 0 then return

    progressUrl = m.serverUrl + "/playback-progress/" + m.transcodeId + "?key=" + m.apiKey

    m.progressTask.control = "stop"
    m.progressTask.progressUrl = progressUrl
    m.progressTask.position = m.currentPosition
    m.progressTask.duration = m.currentDuration
    m.progressTask.functionName = "doReport"
    m.progressTask.control = "run"
end sub

' ---- Auto-Advance ----

function getNextEpisode() as object
    if m.playlistSeasons = invalid or m.playlistSeasons.count() = 0 then return invalid

    seasonIdx = m.playlistSeasonIndex
    epIdx = m.playlistEpisodeIndex

    if seasonIdx >= m.playlistSeasons.count() then return invalid
    season = m.playlistSeasons[seasonIdx]
    if season = invalid or season.episodes = invalid then return invalid

    ' Try next episode in same season
    if epIdx + 1 < season.episodes.count()
        return {
            seasonIndex: seasonIdx,
            episodeIndex: epIdx + 1,
            episode: season.episodes[epIdx + 1],
            season: season
        }
    end if

    ' Try first episode of next season
    if seasonIdx + 1 < m.playlistSeasons.count()
        nextSeason = m.playlistSeasons[seasonIdx + 1]
        if nextSeason <> invalid and nextSeason.episodes <> invalid and nextSeason.episodes.count() > 0
            return {
                seasonIndex: seasonIdx + 1,
                episodeIndex: 0,
                episode: nextSeason.episodes[0],
                season: nextSeason
            }
        end if
    end if

    return invalid
end function

sub playNextEpisode(nextEp as object)
    ep = nextEp.episode
    season = nextEp.season

    epLabel = "S" + str(season.seasonNumber).trim() + "E" + str(ep.episodeNumber).trim()
    epName = ep.name
    if epName = invalid or epName = "" then epName = "Episode " + str(ep.episodeNumber).trim()

    print "[MM " ; mmts() ; "] VideoPlayerScreen: auto-advancing to " ; epLabel ; " " ; epName

    ' Build new play content — setting this triggers onPlayContent()
    content = {
        name: m.showName + " - " + epLabel + " " + epName,
        showName: m.showName,
        serverUrl: m.serverUrl,
        apiKey: m.apiKey,
        transcodeId: ep.transcodeId,
        streamUrl: ep.streamUrl,
        subtitleUrl: ep.subtitleUrl,
        bifUrl: ep.bifUrl,
        quality: ep.quality,
        resumePosition: ep.resumePosition,
        mediaType: "TV",
        seasonsData: m.playlistSeasons,
        seasonIndex: nextEp.seasonIndex,
        episodeIndex: nextEp.episodeIndex
    }

    ' Signal MainScene about the auto-advance (for logging/tracking)
    m.top.nextEpisodeStarted = content

    m.top.playContent = content
end sub

' ---- Stop Playback ----

sub stopPlayback()
    print "[MM " ; mmts() ; "] VideoPlayerScreen: stopping playback"
    m.exiting = true
    reportProgress()
    m.videoPlayer.control = "stop"
end sub

' ---- Key Handling ----

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        stopPlayback()
        m.top.playbackFinished = true
        return true
    end if

    if key = "up"
        if m.skipCreditsVisible
            doSkipCredits()
            return true
        end if
        if m.skipIntroVisible
            print "[MM " ; mmts() ; "] VideoPlayerScreen: UP pressed — skipping intro"
            doSkipIntro()
            return true
        end if
    end if

    if key = "options"
        ' Toggle CC
        toggleCC()
        return true
    end if

    return false
end function
