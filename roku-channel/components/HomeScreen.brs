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

    m.serverUrl = ""
    m.apiKey = ""
    m.username = ""

    ' Focus state: "rowList", "search", "profile", "panel"
    m.focusTarget = "rowList"
    ' Panel button index: 0=Switch, 1=Remove
    m.panelIndex = 0
    m.panelOpen = false
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

    ' Build carousel content
    buildCarousels()
end sub

sub buildCarousels()
    print "[MM] HomeScreen: building carousels"

    ' Use server poster for real content, bundled placeholder as fallback
    posterUri = "pkg:/images/placeholder-poster.png"
    if m.serverUrl <> "" and m.apiKey <> ""
        posterUri = m.serverUrl + "/posters/w500/545?key=" + m.apiKey
        print "[MM] HomeScreen: using server poster: " ; posterUri
    end if

    ' Build row content
    rowContent = createObject("roSGNode", "ContentNode")

    carouselTitles = ["Resume Playing", "Recently Added", "Wish List"]

    for each title in carouselTitles
        rowNode = createObject("roSGNode", "ContentNode")
        rowNode.title = title

        ' Add 8 placeholder poster items per row
        for i = 1 to 8
            itemNode = createObject("roSGNode", "ContentNode")
            itemNode.HDPosterUrl = posterUri
            rowNode.appendChild(itemNode)
        end for

        rowContent.appendChild(rowNode)
    end for

    m.rowList.content = rowContent
    setFocusTarget("rowList")

    print "[MM] HomeScreen: carousels built — 3 rows, 8 items each"
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
            ' Search is non-functional for now
            print "[MM] HomeScreen: search selected (not implemented)"
            return true
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
