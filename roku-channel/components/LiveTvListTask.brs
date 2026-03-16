function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] LiveTvListTask: init"
end sub

sub doFetch()
    url = m.top.channelsUrl
    if url = "" or url = invalid
        m.top.channelsError = "No channels URL set"
        return
    end if

    print "[MM " ; mmts() ; "] LiveTvListTask: fetching " ; url

    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl(url)
    transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    transfer.InitClientCertificates()

    xferPort = CreateObject("roMessagePort")
    transfer.SetMessagePort(xferPort)

    if transfer.AsyncGetToString()
        msg = wait(15000, xferPort)
        if type(msg) = "roUrlEvent"
            httpCode = msg.GetResponseCode()
            response = msg.GetString()
        else
            httpCode = -1
            response = ""
        end if
    else
        httpCode = -1
        response = ""
    end if

    print "[MM " ; mmts() ; "] LiveTvListTask: response code=" ; str(httpCode).trim() ; " size=" ; str(len(response)).trim()

    if httpCode <> 200 or response = "" or response = invalid
        m.top.channelsError = "Failed to fetch channels (HTTP " + str(httpCode).trim() + ")"
        return
    end if

    json = parseJSON(response)
    if json = invalid
        m.top.channelsError = "Invalid JSON in channels response"
        return
    end if

    if json.channels = invalid
        m.top.channelsError = "Channels response missing channels array"
        return
    end if

    print "[MM " ; mmts() ; "] LiveTvListTask: parsed " ; str(json.channels.count()).trim() ; " channels"
    m.top.channelsResult = json
end sub
