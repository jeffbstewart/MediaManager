function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] SearchTask: init"
end sub

sub doFetch()
    url = m.top.searchUrl
    if url = "" or url = invalid
        m.top.searchError = "No search URL set"
        return
    end if

    print "[MM " ; mmts() ; "] SearchTask: fetching " ; url

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

    print "[MM " ; mmts() ; "] SearchTask: response code=" ; str(httpCode).trim() ; " size=" ; str(len(response)).trim()

    if httpCode <> 200 or response = "" or response = invalid
        m.top.searchError = "Search failed (HTTP " + str(httpCode).trim() + ")"
        return
    end if

    json = parseJSON(response)
    if json = invalid
        m.top.searchError = "Invalid JSON in search response"
        return
    end if

    if json.results = invalid
        m.top.searchError = "Search response missing results"
        return
    end if

    print "[MM " ; mmts() ; "] SearchTask: parsed " ; str(json.results.count()).trim() ; " results"
    m.top.searchResult = json
end sub
