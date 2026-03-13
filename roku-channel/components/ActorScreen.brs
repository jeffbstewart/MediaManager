sub init()
    print "[MM] ActorScreen: init"
    m.headerLabel = m.top.findNode("headerLabel")
    m.actorHeadshot = m.top.findNode("actorHeadshot")
    m.hintLabel = m.top.findNode("hintLabel")
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

sub onActorDataChanged()
    data = m.top.actorData
    if data = invalid then return

    personId = 0
    if data.tmdbPersonId <> invalid
        personId = data.tmdbPersonId
    end if
    if personId = 0 then return

    m.headerLabel.text = data.name
    m.loadingLabel.visible = true
    m.itemsGrid.visible = false

    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey
    detailUrl = serverUrl + "/roku/actor/" + str(personId).trim() + ".json?key=" + apiKey

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

    if data.headshotUrl <> invalid and data.headshotUrl <> ""
        m.actorHeadshot.uri = data.headshotUrl
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
    print "[MM] ActorScreen: error — " ; errorMsg
    m.loadingLabel.text = "Failed to load actor"
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
    print "[MM] ActorScreen: selected — " ; item.name

    ' Only allow selection of playable items
    if item.playable <> invalid and not item.playable
        print "[MM] ActorScreen: item not playable"
        return
    end if

    if item.mediaType <> invalid and item.mediaType = "TV"
        m.top.episodePickerRequested = item
    else
        m.top.playRequested = item
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    ' Options key (★) on focused item → add wish
    if key = "options"
        focused = m.itemsGrid.rowItemFocused
        selectedIndex = -1
        if focused <> invalid then selectedIndex = focused[1]
        if selectedIndex >= 0 and selectedIndex < m.items.count()
            item = m.items[selectedIndex]
            if item.wished <> invalid and not item.wished
                print "[MM] ActorScreen: wish requested for " ; item.name
                m.top.wishRequested = item
            else
                print "[MM] ActorScreen: item already wished"
            end if
            return true
        end if
    end if

    return false
end function
