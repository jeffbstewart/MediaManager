sub init()
    print "[MM] VideoPlayerScreen: init"
    m.videoPlayer = m.top.findNode("videoPlayer")
    m.progressTask = m.top.findNode("progressTask")

    m.videoPlayer.observeField("state", "onVideoState")
    m.videoPlayer.observeField("position", "onVideoPosition")

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

    print "[MM] VideoPlayerScreen: play request — " ; m.contentTitle ; " (transcode " ; m.transcodeId ; ")"

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
    print "[MM] VideoPlayerScreen: resume available at " ; timeStr

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
        print "[MM] VideoPlayerScreen: resuming from " ; str(m.resumePosition).trim()
        startPlayback(m.resumePosition)
    else
        ' Start over
        print "[MM] VideoPlayerScreen: starting from beginning"
        startPlayback(0)
    end if
end sub

sub startPlayback(startPos as integer)
    streamUrl = m.serverUrl + "/stream/" + m.transcodeId + "?key=" + m.apiKey
    print "[MM] VideoPlayerScreen: stream URL = " ; streamUrl

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
        print "[MM] VideoPlayerScreen: BIF trick play — " ; m.bifUrl
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

    print "[MM] VideoPlayerScreen: playback started"
end sub

' ---- Subtitle Configuration ----

sub configureSubtitles(videoContent as object, subtitleUrl as string)
    print "[MM] VideoPlayerScreen: subtitles — " ; subtitleUrl

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
        print "[MM] VideoPlayerScreen: CC turned on"
    else
        m.videoPlayer.globalCaptionMode = "Off"
        print "[MM] VideoPlayerScreen: CC turned off"
    end if
end sub

' ---- Video State / Position ----

sub onVideoState()
    state = m.videoPlayer.state
    print "[MM] VideoPlayerScreen: video state → " ; state

    if state = "stopped" or state = "finished" or state = "error"
        ' Guard against double-fire (back key sets playbackFinished, then stop state fires again)
        if m.exiting then return

        ' Report final position before exiting
        reportProgress()

        if state = "error"
            print "[MM] VideoPlayerScreen: playback error — " ; m.videoPlayer.errorMsg
        end if

        if state = "finished"
            ' Try auto-advance to next episode
            nextEp = getNextEpisode()
            if nextEp <> invalid
                m.exiting = true ' Guard against spurious state events during transition
                playNextEpisode(nextEp)
                return
            end if
            print "[MM] VideoPlayerScreen: playback finished (last episode)"
        end if

        m.exiting = true
        m.videoPlayer.control = "stop"
        m.top.playbackFinished = true
    end if
end sub

sub onVideoPosition()
    m.currentPosition = m.videoPlayer.position
    m.currentDuration = m.videoPlayer.duration

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

    print "[MM] VideoPlayerScreen: auto-advancing to " ; epLabel ; " " ; epName

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
    print "[MM] VideoPlayerScreen: stopping playback"
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

    if key = "options"
        ' Toggle CC
        toggleCC()
        return true
    end if

    return false
end function
