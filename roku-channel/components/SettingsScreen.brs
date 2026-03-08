sub init()
    print "[MM] SettingsScreen: init"
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
    m.apiKey = ""

    ' Load existing values from registry
    reg = CreateObject("roRegistrySection", "MediaManager")
    m.serverUrl = reg.Read("serverUrl")
    m.apiKey = reg.Read("apiKey")

    ' Determine initial state
    if m.serverUrl <> "" and m.apiKey <> ""
        ' Already configured — show reconfigure options
        print "[MM] SettingsScreen: already configured, showing options"
        showConfigured()
    else if m.serverUrl <> ""
        ' Have server URL but no key — go straight to pairing
        print "[MM] SettingsScreen: have server URL, starting pairing"
        startPairing()
    else
        ' Nothing configured — try SSDP discovery
        print "[MM] SettingsScreen: no config, starting SSDP discovery"
        startDiscovery()
    end if
end sub

' ---- State: Discovery ----

sub startDiscovery()
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
        print "[MM] SettingsScreen: discovered server at " ; url
        m.serverUrl = url
        m.instructionLabel.text = "Found server: " + url
        startPairing()
    else
        print "[MM] SettingsScreen: SSDP discovery failed"
        m.instructionLabel.text = "Could not find server automatically. Enter the address manually."
        m.buttonGroup.buttons = ["Enter Server Address"]
        m.buttonGroup.setFocus(true)
    end if
end sub

' ---- State: Pairing ----

sub startPairing()
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

    print "[MM] SettingsScreen: pair code received: " ; code

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
        print "[MM] SettingsScreen: paired! user=" ; username

        ' Save to registry
        reg = CreateObject("roRegistrySection", "MediaManager")
        reg.Write("serverUrl", m.serverUrl)
        reg.Write("apiKey", token)
        reg.Write("username", username)
        reg.Flush()

        m.apiKey = token
        hideQrCode()
        m.instructionLabel.text = "Connected as " + username + "!"
        m.statusLabel.text = "Device linked successfully."
        m.statusLabel.color = "#22c55e"
        m.statusLabel.visible = true

        ' Notify parent to fetch feed
        m.top.settingsSaved = true

    else if status = "expired"
        print "[MM] SettingsScreen: pair code expired"
        hideQrCode()
        m.instructionLabel.text = "Pairing code expired. Try again."
        m.buttonGroup.buttons = ["Pair Again", "Enter Key Manually", "Cancel"]
        m.buttonGroup.setFocus(true)
    end if
end sub

sub onPairError()
    errorMsg = m.pairTask.pairError
    if errorMsg <> "" and errorMsg <> invalid
        print "[MM] SettingsScreen: pair error: " ; errorMsg
        showStatus(errorMsg)
        m.buttonGroup.buttons = ["Retry", "Enter Server Address", "Cancel"]
        m.buttonGroup.setFocus(true)
    end if
end sub

' ---- State: Already Configured ----

sub showConfigured()
    maskedKey = ""
    if m.apiKey <> "" and m.apiKey <> invalid
        if len(m.apiKey) > 8
            maskedKey = left(m.apiKey, 8) + "..."
        else
            maskedKey = m.apiKey
        end if
    end if

    reg = CreateObject("roRegistrySection", "MediaManager")
    username = reg.Read("username")
    if username = "" or username = invalid
        username = "unknown"
    end if

    m.instructionLabel.text = "Connected to " + m.serverUrl + " as " + username
    m.buttonGroup.buttons = ["Re-pair Device", "Change Server", "Save & Connect"]
    m.buttonGroup.setFocus(true)
    hideQrCode()
end sub

' ---- Button Handler ----

sub onButtonSelected()
    index = m.buttonGroup.buttonSelected
    label = m.buttonGroup.buttons[index]

    print "[MM] SettingsScreen: button pressed: " ; label

    if label = "Enter Server Address Manually" or label = "Enter Server Address" or label = "Change Server"
        showKeyboard("Enter Server URL", "e.g. http://192.168.1.100:8080", m.serverUrl, "serverUrl")
    else if label = "Enter Key Manually"
        showKeyboard("Enter API Key", "UUID from Transcodes > Settings", "", "apiKey")
    else if label = "Pair Again" or label = "Re-pair Device" or label = "Retry"
        startPairing()
    else if label = "Save & Connect"
        saveSettings()
    else if label = "Cancel"
        if m.serverUrl <> "" and m.apiKey <> ""
            m.top.settingsSaved = true
        end if
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
            print "[MM] SettingsScreen: server URL set to " ; text
            ' Start pairing with new URL
            startPairing()
        else if m.pendingField = "apiKey"
            m.apiKey = text.trim()
            print "[MM] SettingsScreen: API key entered manually"
            ' Save immediately
            reg = CreateObject("roRegistrySection", "MediaManager")
            reg.Write("serverUrl", m.serverUrl)
            reg.Write("apiKey", m.apiKey)
            reg.Flush()
            m.top.settingsSaved = true
        end if
    else
        print "[MM] SettingsScreen: keyboard cancelled for " ; m.pendingField
    end if

    m.buttonGroup.setFocus(true)
end sub

' ---- Helpers ----

sub saveSettings()
    if m.serverUrl = "" or m.serverUrl = invalid
        showStatus("Server URL is required.")
        return
    end if
    if m.apiKey = "" or m.apiKey = invalid
        showStatus("API Key is required. Use QR pairing or enter manually.")
        return
    end if

    reg = CreateObject("roRegistrySection", "MediaManager")
    reg.Write("serverUrl", m.serverUrl)
    reg.Write("apiKey", m.apiKey)
    reg.Flush()

    print "[MM] SettingsScreen: settings saved to registry"
    m.statusLabel.visible = false
    m.top.settingsSaved = true
end sub

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
