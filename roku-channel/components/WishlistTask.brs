sub init()
    print "[MM] WishlistTask: init"
end sub

sub doPost()
    url = m.top.wishUrl
    body = m.top.wishBody
    if url = "" or url = invalid
        m.top.wishError = "No wishlist URL set"
        return
    end if

    print "[MM] WishlistTask: posting to " ; url

    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl(url)
    transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    transfer.InitClientCertificates()
    transfer.AddHeader("Content-Type", "application/json")

    xferPort = CreateObject("roMessagePort")
    transfer.SetMessagePort(xferPort)

    if transfer.AsyncPostFromString(body)
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

    print "[MM] WishlistTask: response code=" ; str(httpCode).trim()

    if httpCode <> 200 or response = "" or response = invalid
        m.top.wishError = "Wishlist request failed (HTTP " + str(httpCode).trim() + ")"
        return
    end if

    json = parseJSON(response)
    if json = invalid
        m.top.wishError = "Invalid JSON in wishlist response"
        return
    end if

    m.top.wishResult = json
end sub
