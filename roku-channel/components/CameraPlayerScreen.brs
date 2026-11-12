function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] CameraPlayerScreen: init"
    m.cameraPlayer = m.top.findNode("cameraPlayer")
    m.cameraNameLabel = m.top.findNode("cameraNameLabel")

    m.cameraPlayer.observeField("state", "onPlayerStateChanged")

    ' Track retry attempts for transient HLS errors
    m.retryCount = 0
    m.maxRetries = 3
    m.currentContent = invalid

    ' Retry timer
    m.retryTimer = CreateObject("roSGNode", "Timer")
    m.retryTimer.duration = 2
    m.retryTimer.repeat = false
    m.retryTimer.observeField("fire", "onRetryTimer")
    m.top.appendChild(m.retryTimer)
end sub

sub onPlayContent()
    content = m.top.playContent
    if content = invalid then return

    cameraName = ""
    if content.name <> invalid then cameraName = content.name
    streamUrl = ""
    if content.streamUrl <> invalid then streamUrl = content.streamUrl

    print "[MM " ; mmts() ; "] CameraPlayerScreen: playing " ; cameraName ; " — " ; streamUrl

    m.cameraNameLabel.text = cameraName
    m.currentContent = content
    m.retryCount = 0

    startStream(streamUrl, cameraName)
end sub

sub startStream(streamUrl as string, cameraName as string)
    ' Build content node for the Video player
    videoContent = CreateObject("roSGNode", "ContentNode")
    videoContent.url = streamUrl
    videoContent.streamFormat = "hls"
    videoContent.live = true
    videoContent.title = cameraName

    m.cameraPlayer.content = videoContent
    m.cameraPlayer.control = "play"
    m.cameraPlayer.setFocus(true)
end sub

sub onPlayerStateChanged()
    state = m.cameraPlayer.state
    print "[MM " ; mmts() ; "] CameraPlayerScreen: player state = " ; state

    if state = "error"
        errorCode = ""
        errorMsg = ""
        if m.cameraPlayer.errorCode <> invalid then errorCode = str(m.cameraPlayer.errorCode).trim()
        if m.cameraPlayer.errorMsg <> invalid then errorMsg = m.cameraPlayer.errorMsg
        print "[MM " ; mmts() ; "] CameraPlayerScreen: error code=" ; errorCode ; " msg=" ; errorMsg

        ' Retry on transient errors (HLS may need time to start)
        if m.retryCount < m.maxRetries
            m.retryCount = m.retryCount + 1
            print "[MM " ; mmts() ; "] CameraPlayerScreen: retrying (" ; str(m.retryCount).trim() ; "/" ; str(m.maxRetries).trim() ; ")..."
            m.cameraPlayer.control = "stop"
            m.retryTimer.control = "start"
        else
            print "[MM " ; mmts() ; "] CameraPlayerScreen: max retries exceeded, giving up"
            stopPlayback()
        end if
    else if state = "finished"
        ' Live streams shouldn't "finish" but handle it — retry
        if m.retryCount < m.maxRetries
            m.retryCount = m.retryCount + 1
            print "[MM " ; mmts() ; "] CameraPlayerScreen: stream finished, retrying..."
            m.cameraPlayer.control = "stop"
            m.retryTimer.control = "start"
        else
            stopPlayback()
        end if
    else if state = "playing"
        ' Reset retry count on successful playback
        m.retryCount = 0
    end if
end sub

sub onRetryTimer()
    if m.currentContent = invalid then return

    streamUrl = ""
    cameraName = ""
    if m.currentContent.streamUrl <> invalid then streamUrl = m.currentContent.streamUrl
    if m.currentContent.name <> invalid then cameraName = m.currentContent.name

    print "[MM " ; mmts() ; "] CameraPlayerScreen: retry timer fired, restarting stream"
    startStream(streamUrl, cameraName)
end sub

sub stopPlayback()
    m.cameraPlayer.control = "stop"
    m.retryTimer.control = "stop"
    m.top.playbackFinished = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        print "[MM " ; mmts() ; "] CameraPlayerScreen: back pressed, stopping stream"
        stopPlayback()
        return true
    end if

    return false
end function
