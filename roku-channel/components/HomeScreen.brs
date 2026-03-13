sub init()
    print "[MM] HomeScreen: init"
    m.welcomeLabel = m.top.findNode("welcomeLabel")
    m.serverLabel = m.top.findNode("serverLabel")
    m.profileAvatar = m.top.findNode("profileAvatar")
    m.profileLetter = m.top.findNode("profileLetter")
    m.profileName = m.top.findNode("profileName")
    m.profileFocusRing = m.top.findNode("profileFocusRing")
end sub

sub onProfileContentChanged()
    profile = m.top.profileContent
    if profile = invalid then return

    username = profile.username
    serverUrl = profile.serverUrl
    avatarColor = profile.avatarColor

    if username = invalid then username = "User"
    if serverUrl = invalid then serverUrl = ""
    if avatarColor = invalid then avatarColor = "#6366f1"

    print "[MM] HomeScreen: profile loaded — " ; username ; " @ " ; serverUrl

    m.welcomeLabel.text = "Welcome, " + username
    m.serverLabel.text = "Connected to " + serverUrl

    ' Update profile widget
    m.profileAvatar.color = avatarColor
    if username <> ""
        m.profileLetter.text = ucase(left(username, 1))
    else
        m.profileLetter.text = "?"
    end if
    m.profileName.text = username
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "options"
        ' * button — switch profiles
        print "[MM] HomeScreen: options key — requesting profile switch"
        m.top.switchProfileRequested = true
        return true
    end if

    if key = "OK"
        ' Select on profile widget area — also switch profiles
        print "[MM] HomeScreen: OK key — requesting profile switch"
        m.top.switchProfileRequested = true
        return true
    end if

    return false
end function
