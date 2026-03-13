sub init()
end sub

sub doTask()
    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey
    contentId = m.top.contentId
    position = m.top.position
    duration = m.top.duration

    if serverUrl = "" or apiKey = "" or contentId = "" then return

    url = serverUrl + "/playback-progress/" + contentId + "?key=" + apiKey
    body = "{" + chr(34) + "position" + chr(34) + ":" + position + "," + chr(34) + "duration" + chr(34) + ":" + duration + "}"

    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl(url)
    transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    transfer.InitClientCertificates()
    transfer.AddHeader("Content-Type", "application/json")

    responseCode = transfer.PostFromString(body)
    print "[MM] ProgressTask: reported position=" ; position ; " duration=" ; duration ; " response=" ; str(responseCode).trim()
end sub
