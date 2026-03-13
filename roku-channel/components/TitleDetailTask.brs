sub init()
    print "[MM] TitleDetailTask: init"
end sub

sub doFetch()
    url = m.top.detailUrl
    if url = invalid or url = ""
        m.top.detailError = "No URL provided"
        return
    end if

    print "[MM] TitleDetailTask: fetching " ; url

    xfer = createObject("roUrlTransfer")
    xfer.setUrl(url)
    xfer.setCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.initClientCertificates()
    xfer.setPort(createObject("roMessagePort"))
    xfer.enableEncodings(true)

    if xfer.asyncGetToString()
        msg = wait(15000, xfer.getPort())
        if msg <> invalid
            code = msg.getResponseCode()
            body = msg.getString()
            print "[MM] TitleDetailTask: response code=" ; str(code).trim() ; " body size=" ; str(len(body)).trim()

            if code = 200
                json = parseJSON(body)
                if json <> invalid
                    m.top.detailResult = json
                else
                    m.top.detailError = "Failed to parse JSON"
                end if
            else
                m.top.detailError = "HTTP " + str(code).trim()
            end if
        else
            m.top.detailError = "Request timed out"
        end if
    else
        m.top.detailError = "Failed to start request"
    end if
end sub
