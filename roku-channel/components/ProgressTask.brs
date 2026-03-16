function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] ProgressTask: init"
end sub

sub doReport()
    url = m.top.progressUrl
    if url = "" or url = invalid
        print "[MM " ; mmts() ; "] ProgressTask: no URL set, skipping"
        return
    end if

    position = m.top.position
    duration = m.top.duration

    body = "{" + chr(34) + "position" + chr(34) + ":" + str(position).trim() + "," + chr(34) + "duration" + chr(34) + ":" + str(duration).trim() + "}"

    print "[MM " ; mmts() ; "] ProgressTask: reporting position=" ; str(position).trim() ; " duration=" ; str(duration).trim() ; " to " ; url

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
            print "[MM " ; mmts() ; "] ProgressTask: server responded " ; str(code).trim()
        else
            print "[MM " ; mmts() ; "] ProgressTask: request timed out"
        end if
    else
        print "[MM " ; mmts() ; "] ProgressTask: failed to start request"
    end if
end sub
