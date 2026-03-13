sub init()
    print "[MM] HomeFeedTask: init"
end sub

sub doFetch()
    url = m.top.feedUrl
    if url = "" or url = invalid
        m.top.feedError = "No feed URL set"
        return
    end if

    print "[MM] HomeFeedTask: fetching " ; url

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

    print "[MM] HomeFeedTask: response code=" ; str(httpCode).trim() ; " size=" ; str(len(response)).trim()

    if httpCode <> 200 or response = "" or response = invalid
        m.top.feedError = "Failed to fetch home feed (HTTP " + str(httpCode).trim() + ")"
        return
    end if

    json = parseJSON(response)
    if json = invalid
        m.top.feedError = "Invalid JSON in home feed response"
        return
    end if

    if json.carousels = invalid
        m.top.feedError = "Home feed missing carousels"
        return
    end if

    print "[MM] HomeFeedTask: parsed " ; str(json.carousels.count()).trim() ; " carousels"
    for each carousel in json.carousels
        itemCount = 0
        if carousel.items <> invalid then itemCount = carousel.items.count()
        print "[MM] HomeFeedTask:   " ; carousel.name ; " — " ; str(itemCount).trim() ; " items"
    end for

    m.top.feedResult = json
end sub
