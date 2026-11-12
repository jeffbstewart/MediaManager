sub init()
    m.avatarCircle = m.top.findNode("avatarCircle")
    m.usernameLabel = m.top.findNode("usernameLabel")
    m.letterLabel = m.top.findNode("letterLabel")
    m.focusRing = m.top.findNode("focusRing")
end sub

sub onFieldChanged()
    username = m.top.username
    isAdd = m.top.isAddButton
    color = m.top.avatarColor

    m.avatarCircle.color = color

    if isAdd
        m.letterLabel.text = "+"
        m.usernameLabel.text = "Add Profile"
    else
        if username <> "" and username <> invalid
            m.letterLabel.text = ucase(left(username, 1))
            m.usernameLabel.text = username
        else
            m.letterLabel.text = "?"
            m.usernameLabel.text = "Unknown"
        end if
    end if
end sub

sub onFocusChanged()
    focused = m.top.isFocused

    if focused
        m.focusRing.visible = true
        m.usernameLabel.color = "#ffffff"
    else
        m.focusRing.visible = false
        m.usernameLabel.color = "#c0c0c0"
    end if
end sub
