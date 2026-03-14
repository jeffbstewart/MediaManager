sub init()
    print "[MM] CameraListTask: init"
end sub

sub doFetch()
    url = m.top.camerasUrl
    if url = "" or url = invalid
        m.top.camerasError = "No cameras URL set"
        return
    end if

    print "[MM] CameraListTask: fetching " ; url

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

    print "[MM] CameraListTask: response code=" ; str(httpCode).trim() ; " size=" ; str(len(response)).trim()

    if httpCode <> 200 or response = "" or response = invalid
        m.top.camerasError = "Failed to fetch cameras (HTTP " + str(httpCode).trim() + ")"
        return
    end if

    json = parseJSON(response)
    if json = invalid
        m.top.camerasError = "Invalid JSON in cameras response"
        return
    end if

    if json.cameras = invalid
        m.top.camerasError = "Cameras response missing cameras array"
        return
    end if

    print "[MM] CameraListTask: parsed " ; str(json.cameras.count()).trim() ; " cameras"
    m.top.camerasResult = json
end sub
