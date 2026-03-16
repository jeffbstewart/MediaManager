function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
end sub

sub onItemContentChanged()
    itemData = m.top.itemContent
    if itemData = invalid then return

    cameraName = ""
    if itemData.hasField("name")
        cameraName = itemData.name
    else if itemData.hasField("title")
        cameraName = itemData.title
    end if

    snapshotUrl = ""
    if itemData.hasField("snapshotUrl")
        snapshotUrl = itemData.snapshotUrl
    end if

    print "[MM " ; mmts() ; "] CameraListItem: name=" ; cameraName ; " snapshot=" ; snapshotUrl

    m.top.findNode("nameLabel").text = cameraName
    if snapshotUrl <> ""
        m.top.findNode("snapshotPoster").uri = snapshotUrl
    end if
end sub
