sub init()
    print "[MM] ProfilePickerScreen: init"
    m.profileGrid = m.top.findNode("profileGrid")
    m.hintLabel = m.top.findNode("hintLabel")
    m.versionLabel = m.top.findNode("versionLabel")

    m.profileItems = []
    m.focusIndex = 0
    m.profileCount = 0

    ' Set version label from manifest
    appInfo = CreateObject("roAppInfo")
    version = appInfo.GetValue("major_version") + "." + appInfo.GetValue("minor_version") + "." + appInfo.GetValue("build_version")
    m.versionLabel.text = "v" + version
    print "[MM] ProfilePickerScreen: version " ; version

    ' Observe profiles field for updates
    m.top.observeField("profiles", "onProfilesChanged")
end sub

sub onProfilesChanged()
    profiles = m.top.profiles
    if profiles = invalid then profiles = []

    print "[MM] ProfilePickerScreen: onProfilesChanged — " ; str(profiles.count()).trim() ; " profile(s)"

    ' Clear existing items
    m.profileGrid.removeChildrenIndex(m.profileGrid.getChildCount(), 0)
    m.profileItems = []
    m.profileCount = profiles.count()

    ' Create profile items
    for i = 0 to profiles.count() - 1
        item = createObject("roSGNode", "ProfileItem")
        item.username = profiles[i].username
        item.avatarColor = profiles[i].avatarColor
        item.isAddButton = false
        item.profileIndex = i
        m.profileGrid.appendChild(item)
        m.profileItems.push(item)
    end for

    ' Add the "+" button
    addItem = createObject("roSGNode", "ProfileItem")
    addItem.username = "Add Profile"
    addItem.avatarColor = "#444444"
    addItem.isAddButton = true
    addItem.profileIndex = -1
    m.profileGrid.appendChild(addItem)
    m.profileItems.push(addItem)

    ' Determine initial focus
    reg = CreateObject("roRegistrySection", "Profiles")
    lastUsedStr = reg.Read("lastUsed")
    if lastUsedStr <> "" and lastUsedStr <> invalid
        lastUsed = val(lastUsedStr)
        if lastUsed >= 0 and lastUsed < profiles.count()
            m.focusIndex = lastUsed
        else
            m.focusIndex = 0
        end if
    else
        m.focusIndex = 0
    end if

    updateFocus()
end sub

sub updateFocus()
    for i = 0 to m.profileItems.count() - 1
        m.profileItems[i].isFocused = (i = m.focusIndex)
    end for

    ' Show hint only when a profile (not "+") is focused
    if m.focusIndex < m.profileCount
        m.hintLabel.visible = true
    else
        m.hintLabel.visible = false
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "left"
        if m.focusIndex > 0
            m.focusIndex = m.focusIndex - 1
            updateFocus()
        end if
        return true
    else if key = "right"
        if m.focusIndex < m.profileItems.count() - 1
            m.focusIndex = m.focusIndex + 1
            updateFocus()
        end if
        return true
    else if key = "OK"
        if m.focusIndex >= 0 and m.focusIndex < m.profileItems.count()
            item = m.profileItems[m.focusIndex]
            if item.isAddButton
                print "[MM] ProfilePickerScreen: add profile requested"
                m.top.addProfileRequested = true
            else
                print "[MM] ProfilePickerScreen: profile selected — index " ; str(m.focusIndex).trim()
                profiles = m.top.profiles
                if profiles <> invalid and m.focusIndex < profiles.count()
                    m.top.selectedProfile = profiles[m.focusIndex]
                end if
            end if
        end if
        return true
    else if key = "options"
        ' Options (*) key — remove profile
        if m.focusIndex < m.profileCount
            profiles = m.top.profiles
            if profiles <> invalid and m.focusIndex < profiles.count()
                profile = profiles[m.focusIndex]
                print "[MM] ProfilePickerScreen: remove requested for " ; profile.username
                showRemoveDialog(profile)
            end if
        end if
        return true
    end if

    return false
end function

sub showRemoveDialog(profile as object)
    dialog = createObject("roSGNode", "StandardMessageDialog")
    dialog.title = "Remove Profile"
    dialog.message = ["Remove " + profile.username + "?"]
    dialog.buttons = ["Remove", "Cancel"]
    dialog.observeField("buttonSelected", "onRemoveDialogButton")
    m.pendingRemoveProfile = profile
    m.top.getScene().dialog = dialog
end sub

sub onRemoveDialogButton()
    dialog = m.top.getScene().dialog
    if dialog = invalid then return

    buttonIndex = dialog.buttonSelected
    m.top.getScene().dialog = invalid

    if buttonIndex = 0
        ' Remove confirmed
        print "[MM] ProfilePickerScreen: remove confirmed for " ; m.pendingRemoveProfile.username
        m.top.removeProfileRequested = m.pendingRemoveProfile

        ' Adjust focus index if needed
        if m.focusIndex >= m.profileCount - 1 and m.focusIndex > 0
            m.focusIndex = m.focusIndex - 1
        end if
    else
        print "[MM] ProfilePickerScreen: remove cancelled"
    end if
end sub
