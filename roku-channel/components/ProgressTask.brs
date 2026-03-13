sub init()
    print "[MM] ProgressTask: init"
end sub

sub doReport()
    url = m.top.progressUrl
    if url = "" or url = invalid
        print "[MM] ProgressTask: no URL set, skipping"
        return
    end if

    position = m.top.position
    duration = m.top.duration

    body = "{" + chr(34) + "position" + chr(34) + ":" + str(position).trim() + "," + chr(34) + "duration" + chr(34) + ":" + str(duration).trim() + "}"

    print "[MM] ProgressTask: reporting position=" ; str(position).trim() ; " duration=" ; str(duration).trim() ; " to " ; url

    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl(url)
    transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    transfer.InitClientCertificates()
    transfer.AddHeader("Content-Type", "application/json")

    xferPort = CreateObject("roMessagePort")
    transfer.SetMessagePort(xferPort)

    if transfer.AsyncPostFromString(body)
        msg = wait(10000, xferPort)
        if type(msg) = "roUrlEvent"
            code = msg.GetResponseCode()
            print "[MM] ProgressTask: server responded " ; str(code).trim()
        else
            print "[MM] ProgressTask: request timed out"
        end if
    else
        print "[MM] ProgressTask: failed to start request"
    end if
end sub
