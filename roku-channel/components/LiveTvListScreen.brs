function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] LiveTvListScreen: init"
    m.channelList = m.top.findNode("channelList")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.noChannelsLabel = m.top.findNode("noChannelsLabel")
    m.liveTvListTask = m.top.findNode("liveTvListTask")

    m.channels = []

    m.liveTvListTask.observeField("channelsResult", "onChannelsResult")
    m.liveTvListTask.observeField("channelsError", "onChannelsError")
    m.channelList.observeField("itemSelected", "onItemSelected")
end sub

sub onChannelsDataChanged()
    data = m.top.channelsData
    if data = invalid then return

    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey

    ' Build the channels URL
    channelsUrl = serverUrl + "/roku/livetv/channels.json?key=" + apiKey

    print "[MM " ; mmts() ; "] LiveTvListScreen: fetching channels from " ; serverUrl

    m.loadingLabel.visible = true
    m.noChannelsLabel.visible = false

    m.liveTvListTask.control = "stop"
    m.liveTvListTask.channelsUrl = channelsUrl
    m.liveTvListTask.functionName = "doFetch"
    m.liveTvListTask.control = "run"
end sub

sub onChannelsResult()
    result = m.liveTvListTask.channelsResult
    if result = invalid then return

    m.loadingLabel.visible = false

    channels = result.channels
    if channels = invalid or channels.count() = 0
        print "[MM " ; mmts() ; "] LiveTvListScreen: no channels"
        m.noChannelsLabel.visible = true
        return
    end if

    m.channels = channels
    m.top.channels = channels
    print "[MM " ; mmts() ; "] LiveTvListScreen: loaded " ; str(channels.count()).trim() ; " channels"

    ' Build content for MarkupList
    content = CreateObject("roSGNode", "ContentNode")
    for each channel in channels
        item = content.createChild("ContentNode")
        networkAff = ""
        if channel.networkAffiliation <> invalid then networkAff = channel.networkAffiliation
        item.addFields({
            guideNumber: channel.guideNumber,
            guideName: channel.guideName,
            networkAffiliation: networkAff,
            streamUrl: channel.streamUrl,
            receptionQuality: channel.receptionQuality,
            channelId: channel.id
        })
    end for

    m.channelList.content = content
    m.channelList.setFocus(true)
end sub

sub onChannelsError()
    errorMsg = m.liveTvListTask.channelsError
    if errorMsg = invalid then return

    print "[MM " ; mmts() ; "] LiveTvListScreen: error - " ; errorMsg
    m.loadingLabel.visible = false
    m.noChannelsLabel.text = "Error: " + errorMsg
    m.noChannelsLabel.visible = true
end sub

sub onItemSelected()
    index = m.channelList.itemSelected
    if index < 0 or index >= m.channels.count() then return

    channel = m.channels[index]
    print "[MM " ; mmts() ; "] LiveTvListScreen: channel selected - " ; channel.guideNumber ; " " ; channel.guideName

    m.top.channelSelected = channel
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "back"
        return false ' Let MainScene handle
    end if

    return false
end function
