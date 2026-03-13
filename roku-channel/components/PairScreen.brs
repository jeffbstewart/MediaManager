sub init()
    print "[MM] PairScreen: init"
    m.buttonGroup = m.top.findNode("buttonGroup")
    m.statusLabel = m.top.findNode("statusLabel")
    m.instructionLabel = m.top.findNode("instructionLabel")
    m.qrCode = m.top.findNode("qrCode")
    m.pairCodeLabel = m.top.findNode("pairCodeLabel")
    m.pairInstructionLabel = m.top.findNode("pairInstructionLabel")
    m.pairTask = m.top.findNode("pairTask")

    m.buttonGroup.observeField("buttonSelected", "onButtonSelected")
    m.pairTask.observeField("discoveredUrl", "onDiscovered")
    m.pairTask.observeField("pairCode", "onPairCodeReceived")
    m.pairTask.observeField("pairStatus", "onPairStatus")
    m.pairTask.observeField("pairError", "onPairError")

    m.serverUrl = ""

    ' Start discovery when screen becomes visible
    m.top.observeField("visible", "onVisibleChanged")
end sub

sub onVisibleChanged()
    if m.top.visible
        print "[MM] PairScreen: now visible, starting discovery"
        m.statusLabel.visible = false
        startDiscovery()
    end if
end sub

' ---- State: Discovery ----

sub startDiscovery()
    print "[MM] PairScreen: starting SSDP discovery"
    m.instructionLabel.text = "Searching for Media Manager on your network..."
    m.buttonGroup.buttons = ["Enter Server Address Manually"]
    m.buttonGroup.setFocus(true)
    hideQrCode()

    m.pairTask.action = "discover"
    m.pairTask.functionName = "doTask"
    m.pairTask.control = "run"
end sub

sub onDiscovered()
    url = m.pairTask.discoveredUrl
    if url <> "" and url <> invalid
        print "[MM] PairScreen: discovered server at " ; url
        m.serverUrl = url
        m.instructionLabel.text = "Found server: " + url
        startPairing()
    else
        print "[MM] PairScreen: SSDP discovery failed"
        m.instructionLabel.text = "Could not find server automatically. Enter the address manually."
        m.buttonGroup.buttons = ["Enter Server Address"]
        m.buttonGroup.setFocus(true)
    end if
end sub

' ---- State: Pairing ----

sub startPairing()
    print "[MM] PairScreen: starting pairing with " ; m.serverUrl
    m.instructionLabel.text = "Requesting pairing code..."
    m.buttonGroup.buttons = ["Cancel"]
    hideQrCode()

    m.pairTask.control = "stop"
    m.pairTask.serverUrl = m.serverUrl
    m.pairTask.action = "start"
    m.pairTask.functionName = "doTask"
    m.pairTask.control = "run"
end sub

sub onPairCodeReceived()
    code = m.pairTask.pairCode
    if code = "" or code = invalid then return

    print "[MM] PairScreen: pair code received: " ; code

    ' Show QR code
    qrUrl = m.serverUrl + "/api/pair/qr?code=" + code
    m.qrCode.uri = qrUrl
    m.qrCode.visible = true
    m.pairCodeLabel.text = code
    m.pairCodeLabel.visible = true
    m.pairInstructionLabel.visible = true

    m.instructionLabel.text = "Scan the QR code with your phone, or enter the code in your browser at:" + chr(10) + m.serverUrl + "/pair?code=" + code
    m.buttonGroup.buttons = ["Enter Key Manually", "Retry", "Cancel"]
    m.buttonGroup.setFocus(true)

    ' Start polling for completion
    m.pairTask.control = "stop"
    m.pairTask.action = "poll"
    m.pairTask.functionName = "doTask"
    m.pairTask.control = "run"
end sub

sub onPairStatus()
    status = m.pairTask.pairStatus
    if status = "paired"
        token = m.pairTask.pairToken
        username = m.pairTask.pairUsername

        if username = invalid then username = ""
        print "[MM] PairScreen: paired! user=" ; username

        hideQrCode()
        m.instructionLabel.text = "Connected as " + username + "!"
        m.statusLabel.text = "Device linked successfully."
        m.statusLabel.color = "#22c55e"
        m.statusLabel.visible = true

        ' Fire pairComplete to parent
        m.top.pairComplete = {
            serverUrl: m.serverUrl,
            apiKey: token,
            username: username
        }

    else if status = "expired"
        print "[MM] PairScreen: pair code expired"
        hideQrCode()
        m.instructionLabel.text = "Pairing code expired. Try again."
        m.buttonGroup.buttons = ["Pair Again", "Enter Key Manually", "Cancel"]
        m.buttonGroup.setFocus(true)
    end if
end sub

sub onPairError()
    errorMsg = m.pairTask.pairError
    if errorMsg <> "" and errorMsg <> invalid
        print "[MM] PairScreen: pair error: " ; errorMsg
        showStatus(errorMsg)
        m.buttonGroup.buttons = ["Retry", "Enter Server Address", "Cancel"]
        m.buttonGroup.setFocus(true)
    end if
end sub

' ---- Button Handler ----

sub onButtonSelected()
    index = m.buttonGroup.buttonSelected
    label = m.buttonGroup.buttons[index]

    print "[MM] PairScreen: button pressed: " ; label

    if label = "Enter Server Address Manually" or label = "Enter Server Address"
        showKeyboard("Enter Server URL", "e.g. http://192.168.1.100:8080", m.serverUrl, "serverUrl")
    else if label = "Enter Key Manually"
        showKeyboard("Enter API Key", "UUID from Transcodes > Settings", "", "apiKey")
    else if label = "Pair Again" or label = "Retry"
        if m.serverUrl <> ""
            startPairing()
        else
            startDiscovery()
        end if
    else if label = "Cancel"
        ' Do nothing — user can press Back to return to ProfilePickerScreen
    end if
end sub

' ---- Keyboard ----

sub showKeyboard(title as string, message as string, currentValue as string, fieldName as string)
    dialog = createObject("roSGNode", "KeyboardDialog")
    dialog.title = title
    dialog.message = [message]
    dialog.buttons = ["OK", "Cancel"]

    if currentValue <> invalid and currentValue <> ""
        dialog.text = currentValue
    end if

    dialog.observeField("buttonSelected", "onKeyboardButton")
    m.pendingField = fieldName
    m.top.getScene().dialog = dialog
end sub

sub onKeyboardButton()
    dialog = m.top.getScene().dialog
    if dialog = invalid then return

    buttonIndex = dialog.buttonSelected
    text = dialog.text

    m.top.getScene().dialog = invalid

    if buttonIndex = 0
        ' OK
        if m.pendingField = "serverUrl"
            if right(text, 1) = "/"
                text = left(text, len(text) - 1)
            end if
            m.serverUrl = text
            print "[MM] PairScreen: server URL set to " ; text
            startPairing()
        else if m.pendingField = "apiKey"
            apiKey = text.trim()
            print "[MM] PairScreen: API key entered manually"
            ' Fire pairComplete — username unknown for manual key entry
            m.top.pairComplete = {
                serverUrl: m.serverUrl,
                apiKey: apiKey,
                username: "User"
            }
        end if
    else
        print "[MM] PairScreen: keyboard cancelled for " ; m.pendingField
    end if

    m.buttonGroup.setFocus(true)
end sub

' ---- Helpers ----

sub showStatus(message as string)
    m.statusLabel.text = message
    m.statusLabel.color = "#ff6b6b"
    m.statusLabel.visible = true
end sub

sub hideQrCode()
    m.qrCode.visible = false
    m.pairCodeLabel.visible = false
    m.pairInstructionLabel.visible = false
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    return false
end function
