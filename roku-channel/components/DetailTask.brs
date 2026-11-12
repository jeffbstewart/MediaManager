function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] DetailTask: init"
end sub

sub doFetch()
    url = m.top.detailUrl
    if url = "" or url = invalid
        m.top.detailError = "No detail URL set"
        return
    end if

    print "[MM " ; mmts() ; "] DetailTask: fetching " ; url

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

    print "[MM " ; mmts() ; "] DetailTask: response code=" ; str(httpCode).trim() ; " size=" ; str(len(response)).trim()

    if httpCode <> 200 or response = "" or response = invalid
        m.top.detailError = "Detail fetch failed (HTTP " + str(httpCode).trim() + ")"
        return
    end if

    json = parseJSON(response)
    if json = invalid
        m.top.detailError = "Invalid JSON in detail response"
        return
    end if

    m.top.detailResult = json
end sub
