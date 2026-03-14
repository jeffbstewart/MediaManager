sub init()
    print "[MM] CameraPlayerScreen: init"
    m.cameraPlayer = m.top.findNode("cameraPlayer")
    m.cameraNameLabel = m.top.findNode("cameraNameLabel")

    m.cameraPlayer.observeField("state", "onPlayerStateChanged")
end sub

sub onPlayContent()
    content = m.top.playContent
    if content = invalid then return

    cameraName = ""
    if content.name <> invalid then cameraName = content.name
    streamUrl = ""
    if content.streamUrl <> invalid then streamUrl = content.streamUrl

    print "[MM] CameraPlayerScreen: playing " ; cameraName ; " — " ; streamUrl

    m.cameraNameLabel.text = cameraName

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
    print "[MM] CameraPlayerScreen: player state = " ; state

    if state = "error"
        errorInfo = m.cameraPlayer.errorMsg
        if errorInfo <> invalid
            print "[MM] CameraPlayerScreen: error — " ; errorInfo
        end if
        ' Return to camera list on error
        stopPlayback()
    else if state = "finished"
        ' Live streams shouldn't "finish" but handle it
        stopPlayback()
    end if
end sub

sub stopPlayback()
    m.cameraPlayer.control = "stop"
    m.top.playbackFinished = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        print "[MM] CameraPlayerScreen: back pressed, stopping stream"
        stopPlayback()
        return true
    end if

    return false
end function
