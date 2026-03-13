sub init()
    print "[MM] MainScene: init"
    m.profilePickerScreen = m.top.findNode("profilePickerScreen")
    m.pairScreen = m.top.findNode("pairScreen")
    m.homeScreen = m.top.findNode("homeScreen")
    m.pairTask = m.top.findNode("pairTask")

    ' Screen stack for Back button navigation
    m.screenStack = []

    ' Avatar color palette for new profiles
    m.avatarColors = [
        "#6366f1", "#ec4899", "#f59e0b", "#10b981",
        "#3b82f6", "#8b5cf6", "#ef4444", "#14b8a6",
        "#f97316", "#06b6d4", "#84cc16", "#e879f9"
    ]

    ' Observe child screen signals
    m.profilePickerScreen.observeField("selectedProfile", "onProfileSelected")
    m.profilePickerScreen.observeField("addProfileRequested", "onAddProfileRequested")
    m.profilePickerScreen.observeField("removeProfileRequested", "onRemoveProfileRequested")
    m.pairScreen.observeField("pairComplete", "onPairComplete")
    m.pairScreen.observeField("cancelRequested", "onPairCancelled")
    m.homeScreen.observeField("switchProfileRequested", "onSwitchProfileRequested")
    m.homeScreen.observeField("removeProfileRequested", "onRemoveProfileFromHome")

    ' Clean up V1 registry if needed
    cleanupV1Registry()

    ' Load profiles and show picker
    profiles = loadProfiles()
    if profiles.count() = 0
        print "[MM] MainScene: no profiles, going directly to PairScreen"
        showScreen(m.pairScreen)
    else
        print "[MM] MainScene: " ; str(profiles.count()).trim() ; " profile(s) found, showing picker"
        m.profilePickerScreen.profiles = profiles
        showScreen(m.profilePickerScreen)
    end if
end sub

' ---- V1 Registry Cleanup ----

sub cleanupV1Registry()
    ' Check if new Profiles section exists
    profilesReg = CreateObject("roRegistrySection", "Profiles")
    countStr = profilesReg.Read("count")

    if countStr <> "" and countStr <> invalid
        ' Profiles section exists, V1 cleanup already done or not needed
        return
    end if

    ' No Profiles data — delete stale V1 keys
    print "[MM] MainScene: cleaning up V1 registry keys"

    v1Reg = CreateObject("roRegistrySection", "MediaManager")
    v1Reg.Delete("serverUrl")
    v1Reg.Delete("apiKey")
    v1Reg.Delete("username")
    v1Reg.Flush()

    bookmarksReg = CreateObject("roRegistrySection", "Bookmarks")
    keys = bookmarksReg.GetKeyList()
    for each key in keys
        bookmarksReg.Delete(key)
    end for
    bookmarksReg.Flush()

    print "[MM] MainScene: V1 registry cleanup complete"
end sub

' ---- Profile Management ----

function loadProfiles() as object
    profiles = []
    reg = CreateObject("roRegistrySection", "Profiles")
    countStr = reg.Read("count")

    if countStr = "" or countStr = invalid
        print "[MM] MainScene: loadProfiles — no profiles in registry"
        return profiles
    end if

    count = val(countStr)
    print "[MM] MainScene: loadProfiles — count=" ; str(count).trim()

    for i = 0 to count - 1
        prefix = "p" + str(i).trim() + "_"
        profile = {
            index: i,
            serverUrl: reg.Read(prefix + "serverUrl"),
            apiKey: reg.Read(prefix + "apiKey"),
            username: reg.Read(prefix + "username"),
            avatarColor: reg.Read(prefix + "avatarColor")
        }
        maskedKey = maskApiKey(profile.apiKey)
        print "[MM] MainScene: loadProfiles — [" ; str(i).trim() ; "] " ; profile.username ; " @ " ; profile.serverUrl ; " key=" ; maskedKey
        profiles.push(profile)
    end for

    return profiles
end function

sub saveProfiles(profiles as object)
    reg = CreateObject("roRegistrySection", "Profiles")

    ' Clear old keys first — delete up to a generous max
    for i = 0 to 19
        prefix = "p" + str(i).trim() + "_"
        reg.Delete(prefix + "serverUrl")
        reg.Delete(prefix + "apiKey")
        reg.Delete(prefix + "username")
        reg.Delete(prefix + "avatarColor")
    end for

    ' Write current profiles
    reg.Write("count", str(profiles.count()).trim())

    for i = 0 to profiles.count() - 1
        prefix = "p" + str(i).trim() + "_"
        reg.Write(prefix + "serverUrl", profiles[i].serverUrl)
        reg.Write(prefix + "apiKey", profiles[i].apiKey)
        reg.Write(prefix + "username", profiles[i].username)
        reg.Write(prefix + "avatarColor", profiles[i].avatarColor)
    end for

    reg.Flush()
    print "[MM] MainScene: saveProfiles — saved " ; str(profiles.count()).trim() ; " profile(s)"
end sub

function addProfile(serverUrl as string, apiKey as string, username as string) as object
    profiles = loadProfiles()

    ' Pick an avatar color (cycle through palette)
    colorIndex = profiles.count() mod m.avatarColors.count()
    avatarColor = m.avatarColors[colorIndex]

    profile = {
        index: profiles.count(),
        serverUrl: serverUrl,
        apiKey: apiKey,
        username: username,
        avatarColor: avatarColor
    }

    profiles.push(profile)
    saveProfiles(profiles)

    print "[MM] MainScene: addProfile — added " ; username ; " (color " ; avatarColor ; ")"
    return profile
end function

sub removeProfile(profileIndex as integer)
    profiles = loadProfiles()

    if profileIndex < 0 or profileIndex >= profiles.count()
        print "[MM] MainScene: removeProfile — index " ; str(profileIndex).trim() ; " out of range"
        return
    end if

    removedName = profiles[profileIndex].username
    profiles.delete(profileIndex)

    ' Re-index
    for i = 0 to profiles.count() - 1
        profiles[i].index = i
    end for

    saveProfiles(profiles)

    ' Adjust lastUsed
    reg = CreateObject("roRegistrySection", "Profiles")
    lastUsedStr = reg.Read("lastUsed")
    if lastUsedStr <> "" and lastUsedStr <> invalid
        lastUsed = val(lastUsedStr)
        if lastUsed >= profiles.count()
            lastUsed = profiles.count() - 1
            if lastUsed < 0 then lastUsed = 0
            reg.Write("lastUsed", str(lastUsed).trim())
            reg.Flush()
        end if
    end if

    print "[MM] MainScene: removeProfile — removed " ; removedName ; " (index " ; str(profileIndex).trim() ; ")"
end sub

' ---- Screen Stack Navigation ----

sub showScreen(screen as object)
    ' Hide current top screen
    if m.screenStack.count() > 0
        m.screenStack[m.screenStack.count() - 1].visible = false
    end if

    screen.visible = true
    screen.setFocus(true)
    m.screenStack.push(screen)
    print "[MM] MainScene: showScreen — stack depth=" ; str(m.screenStack.count()).trim()
end sub

sub hideTopScreen()
    if m.screenStack.count() > 0
        topScreen = m.screenStack.pop()
        topScreen.visible = false
    end if

    ' Show and focus previous screen
    if m.screenStack.count() > 0
        prevScreen = m.screenStack[m.screenStack.count() - 1]
        prevScreen.visible = true
        prevScreen.setFocus(true)
    end if
    print "[MM] MainScene: hideTopScreen — stack depth=" ; str(m.screenStack.count()).trim()
end sub

' ---- Event Handlers ----

sub onProfileSelected()
    profile = m.profilePickerScreen.selectedProfile
    if profile = invalid then return

    print "[MM] MainScene: onProfileSelected — " ; profile.username ; " @ " ; profile.serverUrl

    ' Save lastUsed index
    reg = CreateObject("roRegistrySection", "Profiles")
    reg.Write("lastUsed", str(profile.index).trim())
    reg.Flush()

    ' Show HomeScreen with profile data
    m.homeScreen.profileContent = profile
    showScreen(m.homeScreen)
end sub

sub onAddProfileRequested()
    print "[MM] MainScene: onAddProfileRequested — showing PairScreen"
    showScreen(m.pairScreen)
end sub

sub onRemoveProfileRequested()
    profile = m.profilePickerScreen.removeProfileRequested
    if profile = invalid then return

    profileIndex = profile.index
    if profileIndex = invalid then return

    print "[MM] MainScene: onRemoveProfileRequested — removing index " ; str(profileIndex).trim()
    removeProfile(profileIndex)

    ' Reload profiles and update picker
    profiles = loadProfiles()
    m.profilePickerScreen.profiles = profiles

    if profiles.count() = 0
        print "[MM] MainScene: all profiles removed, showing PairScreen"
        ' Clear stack and go to PairScreen
        m.screenStack = []
        m.profilePickerScreen.visible = false
        showScreen(m.pairScreen)
    end if
end sub

sub onPairComplete()
    result = m.pairScreen.pairComplete
    if result = invalid then return

    print "[MM] MainScene: onPairComplete — " ; result.username ; " @ " ; result.serverUrl

    ' Add the new profile
    profile = addProfile(result.serverUrl, result.apiKey, result.username)

    ' Pop PairScreen
    hideTopScreen()

    ' Reload profiles into picker
    profiles = loadProfiles()

    ' Check if picker is on top of stack
    pickerOnTop = false
    if m.screenStack.count() > 0
        topScreen = m.screenStack[m.screenStack.count() - 1]
        if topScreen.isSameNode(m.profilePickerScreen)
            pickerOnTop = true
        end if
    end if

    if not pickerOnTop
        ' Picker wasn't on stack (first profile from fresh launch) — push it
        m.profilePickerScreen.profiles = profiles
        showScreen(m.profilePickerScreen)
    else
        m.profilePickerScreen.profiles = profiles
    end if

    ' Save lastUsed and auto-select the new profile
    reg = CreateObject("roRegistrySection", "Profiles")
    reg.Write("lastUsed", str(profile.index).trim())
    reg.Flush()

    ' Go directly to HomeScreen
    m.homeScreen.profileContent = profile
    showScreen(m.homeScreen)
end sub

sub onPairCancelled()
    print "[MM] MainScene: onPairCancelled — returning to profile picker"
    hideTopScreen()
end sub

sub onSwitchProfileRequested()
    print "[MM] MainScene: onSwitchProfileRequested — returning to profile picker"
    ' Pop HomeScreen
    hideTopScreen()

    ' Refresh profile data in picker
    profiles = loadProfiles()
    m.profilePickerScreen.profiles = profiles
end sub

sub onRemoveProfileFromHome()
    profile = m.top.findNode("homeScreen").profileContent
    if profile = invalid then return

    profileIndex = profile.index
    if profileIndex = invalid then return

    print "[MM] MainScene: onRemoveProfileFromHome — removing index " ; str(profileIndex).trim()
    removeProfile(profileIndex)

    ' Pop HomeScreen
    hideTopScreen()

    ' Reload profiles and update picker
    profiles = loadProfiles()
    m.profilePickerScreen.profiles = profiles

    if profiles.count() = 0
        print "[MM] MainScene: all profiles removed, showing PairScreen"
        m.screenStack = []
        m.profilePickerScreen.visible = false
        showScreen(m.pairScreen)
    end if
end sub

' ---- Voice Search ----

sub onVoiceSearchQuery()
    query = m.top.voiceSearchQuery
    if query = invalid or query = "" then return

    print "[MM] MainScene: voice search query received: " ; query

    ' Forward to HomeScreen if it's on the stack
    m.homeScreen.searchQuery = query
end sub

' ---- Key Handling ----

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        ' Close dialog if open
        if m.top.dialog <> invalid
            print "[MM] MainScene: back key — closing dialog"
            m.top.dialog = invalid
            return true
        end if

        ' Pop screen stack
        if m.screenStack.count() > 1
            print "[MM] MainScene: back key — popping screen stack"
            hideTopScreen()
            return true
        end if

        ' Last screen — let Roku handle exit
        print "[MM] MainScene: back key — last screen, allowing exit"
        return false
    end if

    return false
end function

' ---- Utility ----

function maskApiKey(key as string) as string
    if key = invalid or key = "" then return "(not set)"
    if len(key) > 8
        return left(key, 8) + "..."
    end if
    return key
end function
