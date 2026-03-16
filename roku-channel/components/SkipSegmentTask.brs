function mmts() as string
    dt = createObject("roDateTime")
    dt.toLocalTime()
    return str(dt.getHours()).trim() + ":" + right("0" + str(dt.getMinutes()).trim(), 2) + ":" + right("0" + str(dt.getSeconds()).trim(), 2)
end function

sub init()
    print "[MM " ; mmts() ; "] SkipSegmentTask: init"
end sub

sub fetchSkipSegments()
    url = m.top.chaptersUrl
    if url = "" or url = invalid
        print "[MM " ; mmts() ; "] SkipSegmentTask: no URL set"
        return
    end if

    print "[MM " ; mmts() ; "] SkipSegmentTask: fetching " ; url

    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl(url)
    transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    transfer.InitClientCertificates()

    xferPort = CreateObject("roMessagePort")
    transfer.SetMessagePort(xferPort)

    if transfer.AsyncGetToString()
        msg = wait(10000, xferPort)
        if type(msg) = "roUrlEvent"
            code = msg.GetResponseCode()
            if code = 200
                body = msg.GetString()
                parseResponse(body)
            else
                print "[MM " ; mmts() ; "] SkipSegmentTask: server returned " ; str(code).trim()
            end if
        else
            print "[MM " ; mmts() ; "] SkipSegmentTask: request timed out"
        end if
    else
        print "[MM " ; mmts() ; "] SkipSegmentTask: failed to start request"
    end if
end sub

sub parseResponse(body as string)
    json = ParseJSON(body)
    if json = invalid
        print "[MM " ; mmts() ; "] SkipSegmentTask: failed to parse JSON"
        return
    end if

    segments = json.skipSegments
    if segments = invalid or segments.count() = 0
        print "[MM " ; mmts() ; "] SkipSegmentTask: no skip segments found"
        return
    end if

    ' Find the first INTRO segment
    for each seg in segments
        segType = seg.type
        if segType = invalid then segType = seg.segment_type
        if segType <> invalid and (lcase(segType) = "intro" or lcase(segType) = "introduction")
            result = {
                type: "INTRO",
                startSeconds: seg.start,
                endSeconds: seg["end"]
            }
            if result.startSeconds = invalid then result.startSeconds = seg.start_seconds
            if result.endSeconds = invalid then result.endSeconds = seg.end_seconds

            print "[MM " ; mmts() ; "] SkipSegmentTask: found INTRO at " ; str(result.startSeconds).trim() ; "-" ; str(result.endSeconds).trim()
            m.top.skipSegments = result
            return
        end if
    end for

    print "[MM " ; mmts() ; "] SkipSegmentTask: no INTRO segment in " ; str(segments.count()).trim() ; " segment(s)"
end sub
