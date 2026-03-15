sub init()
    print "[MM] LiveTvPlayerScreen: init"
    m.liveTvPlayer = m.top.findNode("liveTvPlayer")
    m.channelOverlay = m.top.findNode("channelOverlay")
    m.channelNumberLabel = m.top.findNode("channelNumberLabel")
    m.channelNameLabel = m.top.findNode("channelNameLabel")

    m.loadingOverlay = m.top.findNode("loadingOverlay")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.loadingSubLabel = m.top.findNode("loadingSubLabel")

    m.liveTvPlayer.observeField("state", "onPlayerStateChanged")

    ' Track retry attempts for transient HLS errors
    m.retryCount = 0
    m.maxRetries = 5
    m.currentContent = invalid

    ' Retry timer
    m.retryTimer = CreateObject("roSGNode", "Timer")
    m.retryTimer.duration = 3
    m.retryTimer.repeat = false
    m.retryTimer.observeField("fire", "onRetryTimer")
    m.top.appendChild(m.retryTimer)

    ' Overlay auto-hide timer (show channel info for 4 seconds on tune)
    m.overlayTimer = CreateObject("roSGNode", "Timer")
    m.overlayTimer.duration = 4
    m.overlayTimer.repeat = false
    m.overlayTimer.observeField("fire", "onOverlayTimer")
    m.top.appendChild(m.overlayTimer)
end sub

sub onPlayContent()
    content = m.top.playContent
    if content = invalid then return

    guideNumber = ""
    if content.guideNumber <> invalid then guideNumber = content.guideNumber
    guideName = ""
    if content.guideName <> invalid then guideName = content.guideName
    streamUrl = ""
    if content.streamUrl <> invalid then streamUrl = content.streamUrl

    print "[MM] LiveTvPlayerScreen: playing " ; guideNumber ; " " ; guideName ; " - " ; streamUrl

    m.channelNumberLabel.text = guideNumber
    m.channelNameLabel.text = guideName
    m.currentContent = content
    m.retryCount = 0

    ' Show loading overlay
    m.loadingLabel.text = "Tuning to " + guideNumber + " " + guideName + "..."
    m.loadingSubLabel.text = ""
    m.loadingOverlay.visible = true

    ' Show channel overlay briefly
    showOverlay()

    startStream(streamUrl, guideNumber + " " + guideName)
end sub

sub startStream(streamUrl as string, title as string)
    ' Build content node for the Video player
    videoContent = CreateObject("roSGNode", "ContentNode")
    videoContent.url = streamUrl
    videoContent.streamFormat = "hls"
    videoContent.live = true
    videoContent.title = title

    m.liveTvPlayer.content = videoContent
    m.liveTvPlayer.control = "play"
    m.liveTvPlayer.setFocus(true)
end sub

sub showOverlay()
    m.channelOverlay.visible = true
    m.overlayTimer.control = "stop"
    m.overlayTimer.control = "start"
end sub

sub onOverlayTimer()
    m.channelOverlay.visible = false
end sub

sub onPlayerStateChanged()
    state = m.liveTvPlayer.state
    print "[MM] LiveTvPlayerScreen: player state = " ; state

    if state = "error"
        errorCode = ""
        errorMsg = ""
        if m.liveTvPlayer.errorCode <> invalid then errorCode = str(m.liveTvPlayer.errorCode).trim()
        if m.liveTvPlayer.errorMsg <> invalid then errorMsg = m.liveTvPlayer.errorMsg
        print "[MM] LiveTvPlayerScreen: error code=" ; errorCode ; " msg=" ; errorMsg

        ' Retry on transient errors (HLS stream needs time to start on server)
        if m.retryCount < m.maxRetries
            m.retryCount = m.retryCount + 1
            print "[MM] LiveTvPlayerScreen: retrying (" ; str(m.retryCount).trim() ; "/" ; str(m.maxRetries).trim() ; ")..."
            m.loadingSubLabel.text = "Connecting... (" + str(m.retryCount).trim() + "/" + str(m.maxRetries).trim() + ")"
            m.loadingOverlay.visible = true
            m.liveTvPlayer.control = "stop"
            m.retryTimer.control = "start"
        else
            print "[MM] LiveTvPlayerScreen: max retries exceeded, giving up"
            m.loadingLabel.text = "Channel unavailable"
            m.loadingSubLabel.text = "Press Back to return"
            m.loadingOverlay.visible = true
            stopPlayback()
        end if
    else if state = "finished"
        ' Live streams should not finish - retry
        if m.retryCount < m.maxRetries
            m.retryCount = m.retryCount + 1
            print "[MM] LiveTvPlayerScreen: stream finished, retrying..."
            m.loadingSubLabel.text = "Reconnecting... (" + str(m.retryCount).trim() + "/" + str(m.maxRetries).trim() + ")"
            m.loadingOverlay.visible = true
            m.liveTvPlayer.control = "stop"
            m.retryTimer.control = "start"
        else
            stopPlayback()
        end if
    else if state = "playing"
        ' Reset retry count on successful playback
        m.retryCount = 0
        m.loadingOverlay.visible = false
    end if
end sub

sub onRetryTimer()
    if m.currentContent = invalid then return

    streamUrl = ""
    guideNumber = ""
    guideName = ""
    if m.currentContent.streamUrl <> invalid then streamUrl = m.currentContent.streamUrl
    if m.currentContent.guideNumber <> invalid then guideNumber = m.currentContent.guideNumber
    if m.currentContent.guideName <> invalid then guideName = m.currentContent.guideName

    print "[MM] LiveTvPlayerScreen: retry timer fired, restarting stream"
    startStream(streamUrl, guideNumber + " " + guideName)
end sub

sub stopPlayback()
    m.liveTvPlayer.control = "stop"
    m.retryTimer.control = "stop"
    m.overlayTimer.control = "stop"
    m.top.playbackFinished = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        print "[MM] LiveTvPlayerScreen: back pressed, stopping stream"
        stopPlayback()
        return true
    end if

    ' Channel up/down via left/right on remote
    if key = "right"
        print "[MM] LiveTvPlayerScreen: channel up"
        m.liveTvPlayer.control = "stop"
        m.top.channelStepRequested = 1
        return true
    else if key = "left"
        print "[MM] LiveTvPlayerScreen: channel down"
        m.liveTvPlayer.control = "stop"
        m.top.channelStepRequested = -1
        return true
    end if

    ' Show overlay on OK press (info display)
    if key = "OK"
        showOverlay()
        return true
    end if

    return false
end function
