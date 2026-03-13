sub init()
    print "[MM] VideoPlayerScreen: init"
    m.videoPlayer = m.top.findNode("videoPlayer")
    m.overlay = m.top.findNode("overlay")
    m.overlayTitle = m.top.findNode("overlayTitle")
    m.overlaySubtitle = m.top.findNode("overlaySubtitle")
    m.ccLabel = m.top.findNode("ccLabel")
    m.progressTask = m.top.findNode("progressTask")

    m.videoPlayer.observeField("state", "onVideoState")
    m.videoPlayer.observeField("position", "onVideoPosition")

    m.serverUrl = ""
    m.apiKey = ""
    m.transcodeId = ""
    m.contentTitle = ""
    m.subtitleUrl = ""
    m.ccEnabled = true
    m.lastReportTime = 0
    m.currentPosition = 0
    m.currentDuration = 0
    m.resumePosition = 0
    m.exiting = false
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
    m.resumePosition = 0

    ' Use subtitle URL from play data if provided
    if content.subtitleUrl <> invalid and content.subtitleUrl <> ""
        m.subtitleUrl = content.subtitleUrl
    end if
    if content.resumePosition <> invalid
        m.resumePosition = content.resumePosition
    end if

    print "[MM] VideoPlayerScreen: play request — " ; m.contentTitle ; " (transcode " ; m.transcodeId ; ")"

    ' Set overlay info
    m.overlayTitle.text = m.contentTitle
    subtitleParts = []
    if content.year <> invalid and content.year > 0
        subtitleParts.push(str(content.year).trim())
    end if
    if content.contentRating <> invalid and content.contentRating <> ""
        subtitleParts.push(content.contentRating)
    end if
    if content.quality <> invalid and content.quality <> ""
        subtitleParts.push(content.quality)
    end if
    m.overlaySubtitle.text = subtitleParts.join("  |  ")

    ' Reset state
    m.exiting = false
    m.lastReportTime = 0
    m.currentPosition = 0
    m.currentDuration = 0
    m.overlay.visible = false

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

    ' Set caption mode BEFORE content assignment
    if m.subtitleUrl <> "" and m.ccEnabled
        m.videoPlayer.globalCaptionMode = "On"
        m.ccLabel.text = "CC: On"
    else
        m.videoPlayer.globalCaptionMode = "Off"
        m.ccLabel.text = "CC: Off"
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

    if m.ccEnabled
        m.ccLabel.text = "CC: On"
    else
        m.ccLabel.text = "CC: Off"
    end if
end sub

sub toggleCC()
    m.ccEnabled = not m.ccEnabled
    if m.ccEnabled
        m.videoPlayer.globalCaptionMode = "On"
        m.ccLabel.text = "CC: On"
        print "[MM] VideoPlayerScreen: CC turned on"
    else
        m.videoPlayer.globalCaptionMode = "Off"
        m.ccLabel.text = "CC: Off"
        print "[MM] VideoPlayerScreen: CC turned off"
    end if
end sub

' ---- Video State / Position ----

sub onVideoState()
    state = m.videoPlayer.state
    print "[MM] VideoPlayerScreen: video state → " ; state

    if state = "playing"
        m.overlay.visible = false
    else if state = "paused"
        m.overlay.visible = true
    else if state = "stopped" or state = "finished" or state = "error"
        ' Guard against double-fire (back key sets playbackFinished, then stop state fires again)
        if m.exiting then return

        ' Report final position before exiting
        reportProgress()

        if state = "error"
            print "[MM] VideoPlayerScreen: playback error — " ; m.videoPlayer.errorMsg
        end if

        if state = "finished"
            print "[MM] VideoPlayerScreen: playback finished"
        end if

        m.exiting = true
        m.overlay.visible = false
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

' ---- Stop Playback ----

sub stopPlayback()
    print "[MM] VideoPlayerScreen: stopping playback"
    m.exiting = true
    reportProgress()
    m.videoPlayer.control = "stop"
    m.overlay.visible = false
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

    ' Show overlay on play/pause when paused
    if key = "play"
        if m.videoPlayer.state = "paused"
            m.videoPlayer.control = "resume"
            return true
        else if m.videoPlayer.state = "playing"
            m.videoPlayer.control = "pause"
            return true
        end if
    end if

    return false
end function
