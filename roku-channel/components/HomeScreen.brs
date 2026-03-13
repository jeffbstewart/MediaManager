sub init()
    print "[MM] HomeScreen: init"
    m.profileAvatar = m.top.findNode("profileAvatar")
    m.profileLetter = m.top.findNode("profileLetter")
    m.profileName = m.top.findNode("profileName")
    m.profileFocusRing = m.top.findNode("profileFocusRing")
    m.rowList = m.top.findNode("rowList")
    m.searchBox = m.top.findNode("searchBox")
    m.searchFocusRing = m.top.findNode("searchFocusRing")
    m.profileWidget = m.top.findNode("profileWidget")

    ' Profile dropdown panel
    m.profilePanel = m.top.findNode("profilePanel")
    m.panelUsername = m.top.findNode("panelUsername")
    m.panelServer = m.top.findNode("panelServer")
    m.panelSwitchFocus = m.top.findNode("panelSwitchFocus")
    m.panelRemoveFocus = m.top.findNode("panelRemoveFocus")

    m.searchPlaceholder = m.top.findNode("searchPlaceholder")

    ' Delegate focus when the Group itself receives it (e.g. returning from another screen)
    m.top.observeField("focusedChild", "onFocusChanged")

    ' Feed task
    m.homeFeedTask = m.top.findNode("homeFeedTask")
    m.homeFeedTask.observeField("feedResult", "onFeedResult")
    m.homeFeedTask.observeField("feedError", "onFeedError")
    m.loadingLabel = m.top.findNode("loadingLabel")

    ' Observe item selection on RowList
    m.rowList.observeField("rowItemSelected", "onRowItemSelected")

    ' Store feed data for item lookup
    m.feedCarousels = []

    m.serverUrl = ""
    m.apiKey = ""
    m.username = ""
    m.currentSearch = ""

    ' Focus state: "rowList", "search", "profile", "panel"
    m.focusTarget = "rowList"
    ' Panel button index: 0=Switch, 1=Remove
    m.panelIndex = 0
    m.panelOpen = false
end sub

sub onFocusChanged()
    if m.top.hasFocus()
        print "[MM] HomeScreen: group received focus, delegating to " ; m.focusTarget
        setFocusTarget(m.focusTarget)
    end if
end sub

sub onProfileContentChanged()
    profile = m.top.profileContent
    if profile = invalid then return

    username = profile.username
    serverUrl = profile.serverUrl
    apiKey = profile.apiKey
    avatarColor = profile.avatarColor

    if username = invalid then username = "User"
    if serverUrl = invalid then serverUrl = ""
    if apiKey = invalid then apiKey = ""
    if avatarColor = invalid then avatarColor = "#6366f1"

    print "[MM] HomeScreen: profile loaded — " ; username ; " @ " ; serverUrl

    m.serverUrl = serverUrl
    m.apiKey = apiKey
    m.username = username

    ' Update profile widget
    m.profileAvatar.color = avatarColor
    if username <> ""
        m.profileLetter.text = ucase(left(username, 1))
    else
        m.profileLetter.text = "?"
    end if
    m.profileName.text = username

    ' Update panel info
    m.panelUsername.text = username
    m.panelServer.text = serverUrl

    ' Close panel if open
    closePanel()

    ' Fetch real carousel content from server
    fetchHomeFeed()
end sub

' ---- Feed Fetching ----

sub fetchHomeFeed()
    if m.serverUrl = "" or m.apiKey = ""
        print "[MM] HomeScreen: no server/key, showing empty state"
        return
    end if

    feedUrl = m.serverUrl + "/roku/home.json?key=" + m.apiKey
    print "[MM] HomeScreen: fetching home feed from " ; feedUrl

    m.loadingLabel.visible = true
    m.rowList.visible = false

    m.homeFeedTask.control = "stop"
    m.homeFeedTask.feedUrl = feedUrl
    m.homeFeedTask.functionName = "doFetch"
    m.homeFeedTask.control = "run"
end sub

sub onFeedResult()
    feedData = m.homeFeedTask.feedResult
    if feedData = invalid or feedData.carousels = invalid
        print "[MM] HomeScreen: feed result invalid"
        m.loadingLabel.text = "No content available"
        return
    end if

    m.loadingLabel.visible = false
    m.rowList.visible = true

    ' Store for item lookup on selection
    m.feedCarousels = feedData.carousels

    buildCarouselsFromFeed(feedData.carousels)
end sub

sub onFeedError()
    errorMsg = m.homeFeedTask.feedError
    if errorMsg = invalid or errorMsg = "" then return

    print "[MM] HomeScreen: feed error — " ; errorMsg
    m.loadingLabel.text = "Failed to load content"
    m.loadingLabel.visible = true
end sub

sub buildCarouselsFromFeed(carousels as object)
    print "[MM] HomeScreen: building carousels from feed (" ; str(carousels.count()).trim() ; " rows)"

    rowContent = createObject("roSGNode", "ContentNode")
    totalItems = 0

    for each carousel in carousels
        rowNode = createObject("roSGNode", "ContentNode")
        rowNode.title = carousel.name

        if carousel.items <> invalid
            for each item in carousel.items
                itemNode = createObject("roSGNode", "ContentNode")

                ' Use poster URL from server, fall back to placeholder
                if item.posterUrl <> invalid and item.posterUrl <> ""
                    itemNode.HDPosterUrl = item.posterUrl
                else
                    itemNode.HDPosterUrl = "pkg:/images/placeholder-poster.png"
                end if

                ' Store metadata on the ContentNode for future use
                itemNode.title = item.name
                if item.titleId <> invalid
                    itemNode.addFields({ titleId: item.titleId })
                end if
                if item.transcodeId <> invalid
                    itemNode.addFields({ transcodeId: item.transcodeId })
                end if
                if item.mediaType <> invalid
                    itemNode.addFields({ mediaType: item.mediaType })
                end if
                if item.wishFulfilled <> invalid and item.wishFulfilled = true
                    itemNode.addFields({ wishFulfilled: true })
                end if

                rowNode.appendChild(itemNode)
                totalItems = totalItems + 1
            end for
        end if

        ' Only add rows that have items
        if rowNode.getChildCount() > 0
            rowContent.appendChild(rowNode)
        end if
    end for

    m.rowList.content = rowContent

    setFocusTarget("rowList")

    print "[MM] HomeScreen: carousels built — " ; str(rowContent.getChildCount()).trim() ; " rows, " ; str(totalItems).trim() ; " total items"
end sub

' ---- Item Selection ----

sub onRowItemSelected()
    selected = m.rowList.rowItemSelected
    if selected = invalid then return

    rowIndex = selected[0]
    itemIndex = selected[1]

    print "[MM] HomeScreen: item selected — row=" ; str(rowIndex).trim() ; " item=" ; str(itemIndex).trim()

    ' Look up the item data from the stored feed
    if rowIndex < m.feedCarousels.count()
        carousel = m.feedCarousels[rowIndex]
        if carousel.items <> invalid and itemIndex < carousel.items.count()
            item = carousel.items[itemIndex]
            print "[MM] HomeScreen: playing " ; item.name ; " (titleId=" ; str(item.titleId).trim() ; ")"

            if item.mediaType <> invalid and item.mediaType = "TV"
                ' TV series — always route to episode picker
                m.top.playRequested = item
            else if item.transcodeId <> invalid and item.transcodeId > 0
                ' Movie — play directly
                m.top.playRequested = item
            else
                print "[MM] HomeScreen: no transcodeId for this item, cannot play"
            end if
        end if
    end if
end sub

' ---- Search ----

sub showSearchKeyboard()
    dialog = createObject("roSGNode", "KeyboardDialog")
    dialog.title = "Search"
    dialog.message = ["Enter a title to search for"]
    dialog.buttons = ["Search", "Cancel"]

    if m.currentSearch <> ""
        dialog.text = m.currentSearch
    end if

    dialog.observeField("buttonSelected", "onSearchKeyboardButton")
    m.top.getScene().dialog = dialog
end sub

sub onSearchKeyboardButton()
    dialog = m.top.getScene().dialog
    if dialog = invalid then return

    buttonIndex = dialog.buttonSelected
    text = dialog.text

    m.top.getScene().dialog = invalid

    if buttonIndex = 0 and text <> invalid and text.trim() <> ""
        ' Search pressed
        setSearchText(text.trim())
    else
        print "[MM] HomeScreen: search cancelled"
    end if

    setFocusTarget("search")
end sub

sub onSearchQueryChanged()
    query = m.top.searchQuery
    if query = invalid or query = "" then return

    print "[MM] HomeScreen: voice search received: " ; query
    setSearchText(query)
end sub

sub setSearchText(text as string)
    m.currentSearch = text
    print "[MM] HomeScreen: search set to: " ; text

    ' Show search text in the search box placeholder
    m.searchPlaceholder.text = text
    m.searchPlaceholder.color = "#e0e0e0"

    ' Fire search request to MainScene for navigation to SearchResultsScreen
    m.top.searchRequested = text
end sub

sub clearSearch()
    m.currentSearch = ""
    m.searchPlaceholder.text = "Search"
    m.searchPlaceholder.color = "#666666"
    m.searchBanner.visible = false
    print "[MM] HomeScreen: search cleared"
end sub

' ---- Focus Management ----

sub setFocusTarget(target as string)
    m.focusTarget = target
    print "[MM] HomeScreen: focus → " ; target

    ' Clear all focus indicators
    m.searchFocusRing.visible = false
    m.profileFocusRing.visible = false

    if target = "rowList"
        m.rowList.setFocus(true)
    else if target = "search"
        m.searchBox.setFocus(true)
        m.searchFocusRing.visible = true
    else if target = "profile"
        m.profileWidget.setFocus(true)
        m.profileFocusRing.visible = true
    else if target = "panel"
        m.profilePanel.setFocus(true)
        updatePanelFocus()
    end if
end sub

' ---- Profile Panel ----

sub openPanel()
    print "[MM] HomeScreen: opening profile panel"
    m.panelOpen = true
    m.profilePanel.visible = true
    m.panelIndex = 0
    setFocusTarget("panel")
end sub

sub closePanel()
    print "[MM] HomeScreen: closing profile panel"
    m.panelOpen = false
    m.profilePanel.visible = false
    m.panelSwitchFocus.visible = false
    m.panelRemoveFocus.visible = false
end sub

sub updatePanelFocus()
    m.panelSwitchFocus.visible = (m.panelIndex = 0)
    m.panelRemoveFocus.visible = (m.panelIndex = 1)
end sub

' ---- Key Handling ----

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "options"
        print "[MM] HomeScreen: options key — requesting profile switch"
        m.top.switchProfileRequested = true
        return true
    end if

    ' Handle panel keys when open
    if m.panelOpen
        return handlePanelKey(key)
    end if

    ' Normal navigation
    if m.focusTarget = "rowList"
        if key = "up"
            ' Check if we're on the top row of the RowList
            if m.rowList.rowItemFocused <> invalid
                rowIndex = m.rowList.rowItemFocused[0]
                if rowIndex = 0
                    setFocusTarget("search")
                    return true
                end if
            end if
        end if
    else if m.focusTarget = "search"
        if key = "down"
            setFocusTarget("rowList")
            return true
        else if key = "right"
            setFocusTarget("profile")
            return true
        else if key = "OK"
            showSearchKeyboard()
            return true
        else if key = "replay"
            ' InstantReplay clears current search
            if m.currentSearch <> ""
                clearSearch()
                return true
            end if
        end if
    else if m.focusTarget = "profile"
        if key = "down"
            setFocusTarget("rowList")
            return true
        else if key = "left"
            setFocusTarget("search")
            return true
        else if key = "OK"
            openPanel()
            return true
        end if
    end if

    return false
end function

function handlePanelKey(key as string) as boolean
    if key = "back"
        closePanel()
        setFocusTarget("profile")
        return true
    else if key = "up"
        if m.panelIndex > 0
            m.panelIndex = m.panelIndex - 1
            updatePanelFocus()
        end if
        return true
    else if key = "down"
        if m.panelIndex < 1
            m.panelIndex = m.panelIndex + 1
            updatePanelFocus()
        end if
        return true
    else if key = "OK"
        if m.panelIndex = 0
            ' Switch Account
            print "[MM] HomeScreen: panel — switch account"
            closePanel()
            m.top.switchProfileRequested = true
        else if m.panelIndex = 1
            ' Remove Account
            print "[MM] HomeScreen: panel — remove account"
            closePanel()
            m.top.removeProfileRequested = true
        end if
        return true
    end if
    return true
end function
