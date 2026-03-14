sub init()
    print "[MM] CameraListScreen: init"
    m.cameraList = m.top.findNode("cameraList")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.noCamerasLabel = m.top.findNode("noCamerasLabel")
    m.cameraListTask = m.top.findNode("cameraListTask")
    m.snapshotTimer = m.top.findNode("snapshotTimer")

    m.cameras = []

    m.cameraListTask.observeField("camerasResult", "onCamerasResult")
    m.cameraListTask.observeField("camerasError", "onCamerasError")
    m.cameraList.observeField("itemSelected", "onItemSelected")
    m.snapshotTimer.observeField("fire", "onSnapshotTimer")
end sub

sub onCamerasDataChanged()
    data = m.top.camerasData
    if data = invalid then return

    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey

    ' Build the cameras URL
    camerasUrl = serverUrl + "/roku/cameras.json?key=" + apiKey

    print "[MM] CameraListScreen: fetching cameras from " ; serverUrl

    m.loadingLabel.visible = true
    m.noCamerasLabel.visible = false

    m.cameraListTask.control = "stop"
    m.cameraListTask.camerasUrl = camerasUrl
    m.cameraListTask.functionName = "doFetch"
    m.cameraListTask.control = "run"
end sub

sub onCamerasResult()
    result = m.cameraListTask.camerasResult
    if result = invalid then return

    m.loadingLabel.visible = false

    cameras = result.cameras
    if cameras = invalid or cameras.count() = 0
        print "[MM] CameraListScreen: no cameras"
        m.noCamerasLabel.visible = true
        return
    end if

    m.cameras = cameras
    print "[MM] CameraListScreen: loaded " ; str(cameras.count()).trim() ; " cameras"

    ' Build content for MarkupList
    content = CreateObject("roSGNode", "ContentNode")
    for each camera in cameras
        item = content.createChild("ContentNode")
        item.addFields({
            name: camera.name,
            streamUrl: camera.streamUrl,
            snapshotUrl: camera.snapshotUrl,
            cameraId: camera.id
        })
    end for

    m.cameraList.content = content
    m.cameraList.setFocus(true)

    ' Start snapshot refresh timer
    m.snapshotTimer.control = "start"
end sub

sub onCamerasError()
    errorMsg = m.cameraListTask.camerasError
    if errorMsg = invalid then return

    print "[MM] CameraListScreen: error — " ; errorMsg
    m.loadingLabel.visible = false
    m.noCamerasLabel.text = "Error: " + errorMsg
    m.noCamerasLabel.visible = true
end sub

sub onItemSelected()
    index = m.cameraList.itemSelected
    if index < 0 or index >= m.cameras.count() then return

    camera = m.cameras[index]
    print "[MM] CameraListScreen: camera selected — " ; camera.name

    m.top.cameraSelected = camera

    ' Stop snapshot timer when leaving
    m.snapshotTimer.control = "stop"
end sub

sub onSnapshotTimer()
    ' Refresh snapshot images by updating content URLs with cache buster
    content = m.cameraList.content
    if content = invalid then return

    for i = 0 to content.getChildCount() - 1
        item = content.getChild(i)
        baseUrl = m.cameras[i].snapshotUrl
        ' Add timestamp to bust cache
        if instr(1, baseUrl, "?") > 0
            item.setFields({ snapshotUrl: baseUrl + "&t=" + str(createObject("roDateTime").asSeconds()).trim() })
        else
            item.setFields({ snapshotUrl: baseUrl + "?t=" + str(createObject("roDateTime").asSeconds()).trim() })
        end if
    end for
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        m.snapshotTimer.control = "stop"
        return false ' Let MainScene handle
    end if

    return false
end function
