sub init()
    print "[MM] TagScreen: init"
    m.headerLabel = m.top.findNode("headerLabel")
    m.itemsGrid = m.top.findNode("itemsGrid")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.detailTask = m.top.findNode("detailTask")

    m.detailTask.observeField("detailResult", "onDetailResult")
    m.detailTask.observeField("detailError", "onDetailError")
    m.itemsGrid.observeField("rowItemSelected", "onItemSelected")

    m.top.observeField("focusedChild", "onFocusChanged")

    m.items = []
end sub

sub onFocusChanged()
    if m.top.hasFocus()
        m.itemsGrid.setFocus(true)
    end if
end sub

sub onTagDataChanged()
    data = m.top.tagData
    if data = invalid then return

    itemId = 0
    if data.id <> invalid
        itemId = data.id
    end if
    if itemId = 0 then return

    m.headerLabel.text = data.name
    m.loadingLabel.visible = true
    m.itemsGrid.visible = false

    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey
    detailType = m.top.detailType

    detailUrl = serverUrl + "/roku/" + detailType + "/" + str(itemId).trim() + ".json?key=" + apiKey

    m.detailTask.control = "stop"
    m.detailTask.detailUrl = detailUrl
    m.detailTask.functionName = "doFetch"
    m.detailTask.control = "run"
end sub

sub onDetailResult()
    data = m.detailTask.detailResult
    if data = invalid then return

    m.loadingLabel.visible = false

    if data.name <> invalid
        m.headerLabel.text = data.name
    end if

    m.items = []
    if data.items <> invalid
        m.items = data.items
    end if

    buildGrid()
    m.itemsGrid.visible = true
    m.itemsGrid.setFocus(true)
end sub

sub onDetailError()
    errorMsg = m.detailTask.detailError
    if errorMsg = invalid then return
    print "[MM] TagScreen: error — " ; errorMsg
    m.loadingLabel.text = "Failed to load"
end sub

sub buildGrid()
    rowContent = createObject("roSGNode", "ContentNode")
    rowNode = createObject("roSGNode", "ContentNode")
    rowNode.title = ""

    for each item in m.items
        itemNode = createObject("roSGNode", "ContentNode")

        if item.posterUrl <> invalid and item.posterUrl <> ""
            itemNode.HDPosterUrl = item.posterUrl
        else
            itemNode.HDPosterUrl = "pkg:/images/placeholder-poster.png"
        end if

        itemNode.title = item.name

        rowNode.appendChild(itemNode)
    end for

    if rowNode.getChildCount() > 0
        rowContent.appendChild(rowNode)
    end if

    m.itemsGrid.content = rowContent
    m.itemsGrid.numRows = 1
end sub

sub onItemSelected()
    selected = m.itemsGrid.rowItemSelected
    if selected = invalid then return
    selectedIndex = selected[1]
    if selectedIndex < 0 or selectedIndex >= m.items.count() then return

    item = m.items[selectedIndex]
    print "[MM] TagScreen: selected — " ; item.name

    if item.mediaType <> invalid and item.mediaType = "TV"
        m.top.episodePickerRequested = item
    else
        m.top.playRequested = item
    end if
end sub
