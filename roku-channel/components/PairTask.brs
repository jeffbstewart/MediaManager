sub init()
    print "[MM] PairTask: init"
end sub

' Entry point — dispatches based on m.top.action
sub doTask()
    action = m.top.action
    if action = "discover"
        doDiscover()
    else if action = "start"
        doStartPairing()
    else if action = "poll"
        doPollStatus()
    end if
end sub

' SSDP discovery: send M-SEARCH multicast and listen for mediaManager response
sub doDiscover()
    print "[MM] PairTask: SSDP discovery starting"

    udp = CreateObject("roDatagramSocket")
    if udp = invalid
        print "[MM] PairTask: SSDP — failed to create UDP socket"
        m.top.discoveredUrl = ""
        return
    end if
    print "[MM] PairTask: SSDP — UDP socket created"

    port = CreateObject("roMessagePort")
    udp.SetMessagePort(port)

    ' Get the Roku's own IP — binding to 0.0.0.0 doesn't work for receiving on Roku
    di = CreateObject("roDeviceInfo")
    localIp = di.GetIPAddrs().eth1
    if localIp = invalid or localIp = ""
        ' Try wifi interface
        localIp = di.GetIPAddrs().wifi0
    end if
    if localIp = invalid or localIp = ""
        ' Fallback — iterate all interfaces
        addrs = di.GetIPAddrs()
        for each key in addrs
            if addrs[key] <> "" and addrs[key] <> invalid
                localIp = addrs[key]
                exit for
            end if
        end for
    end if
    print "[MM] PairTask: SSDP — Roku IP: " ; localIp

    ' Bind to Roku's IP on an ephemeral port
    addr = CreateObject("roSocketAddress")
    addr.SetAddress(localIp + ":0")
    bindOk = udp.SetAddress(addr)
    print "[MM] PairTask: SSDP — SetAddress(" ; localIp ; ":0) returned " ; bindOk

    ' Enable receive notifications — required for wait() to get events
    udp.notifyReadable(true)
    print "[MM] PairTask: SSDP — notifyReadable(true) set"

    ' Build M-SEARCH request
    searchTarget = "urn:stewart:service:mediamanager:1"
    msearch = "M-SEARCH * HTTP/1.1" + chr(13) + chr(10)
    msearch = msearch + "HOST: 239.255.255.250:1900" + chr(13) + chr(10)
    msearch = msearch + "MAN: " + chr(34) + "ssdp:discover" + chr(34) + chr(13) + chr(10)
    msearch = msearch + "MX: 3" + chr(13) + chr(10)
    msearch = msearch + "ST: " + searchTarget + chr(13) + chr(10)
    msearch = msearch + chr(13) + chr(10)

    print "[MM] PairTask: SSDP M-SEARCH payload:" + chr(10) + msearch

    ' Send to multicast group
    destAddr = CreateObject("roSocketAddress")
    destAddr.SetHostName("239.255.255.250")
    destAddr.SetPort(1900)
    udp.SetSendToAddress(destAddr)
    print "[MM] PairTask: SSDP — dest address set to " ; destAddr.GetHostName() ; ":" ; str(destAddr.GetPort()).trim()

    bytesSent = udp.SendStr(msearch)
    print "[MM] PairTask: SSDP M-SEARCH sent (" ; str(bytesSent).trim() ; " bytes)"

    if bytesSent <= 0
        print "[MM] PairTask: SSDP — SendStr FAILED, error: " ; str(udp.Status()).trim()
        udp.Close()
        m.top.discoveredUrl = ""
        return
    end if

    ' Listen for responses (try multiple times, 3 seconds each)
    found = false
    for attempt = 1 to 3
        print "[MM] PairTask: SSDP — waiting for response (attempt " ; str(attempt).trim() ; "/3, 3s timeout)"
        msg = wait(3000, port)
        if msg = invalid
            print "[MM] PairTask: SSDP — no response on attempt " ; str(attempt).trim()
        else
            ' roSocketEvent received — read data from the socket itself
            msgType = type(msg)
            print "[MM] PairTask: SSDP — got event type: " ; msgType

            bufCount = udp.GetCountRcvBuf()
            print "[MM] PairTask: SSDP — receive buffer has " ; str(bufCount).trim() ; " bytes"

            if bufCount > 0
                response = udp.ReceiveStr(bufCount)
            else
                response = udp.ReceiveStr(1024)
            end if

            print "[MM] PairTask: SSDP response (attempt " ; str(attempt).trim() ; ", " ; str(len(response)).trim() ; " bytes):"
            print "[MM] PairTask: SSDP response body: " ; response

            ' Parse LOCATION header from response
            location = ""
            lines = response.split(chr(10))
            for each line in lines
                trimmed = line.trim()
                print "[MM] PairTask: SSDP response line: [" ; trimmed ; "]"
                if left(lcase(trimmed), 9) = "location:"
                    location = trimmed.mid(9).trim()
                    ' Remove trailing CR if present
                    if right(location, 1) = chr(13)
                        location = left(location, len(location) - 1)
                    end if
                    print "[MM] PairTask: SSDP — parsed LOCATION: [" ; location ; "]"
                end if
            end for

            if location <> ""
                print "[MM] PairTask: SSDP discovered server at " ; location
                m.top.discoveredUrl = location
                found = true
                exit for
            else
                print "[MM] PairTask: SSDP — response had no LOCATION header, ignoring"
            end if
        end if
    end for

    udp.Close()

    if not found
        print "[MM] PairTask: SSDP — all attempts exhausted, no server found"
        m.top.discoveredUrl = ""
    end if
end sub

' Request a new pairing code from the server
sub doStartPairing()
    serverUrl = m.top.serverUrl
    if serverUrl = "" or serverUrl = invalid
        m.top.pairError = "Server URL not set"
        return
    end if

    url = serverUrl + "/api/pair/start"
    print "[MM] PairTask: requesting pair code from " ; url

    ' Get device name for display on confirmation page
    di = CreateObject("roDeviceInfo")
    deviceName = di.GetModelDisplayName()
    if deviceName = invalid or deviceName = ""
        deviceName = "Roku"
    end if

    body = "{" + chr(34) + "device_name" + chr(34) + ":" + chr(34) + deviceName + chr(34) + "}"

    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl(url)
    transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    transfer.InitClientCertificates()
    transfer.AddHeader("Content-Type", "application/json")
    transfer.SetRequest("POST")

    ' Use async POST: PostFromString returns the HTTP code, not the body.
    ' Instead, set request method to POST and use GetToString-style async.
    xferPort = CreateObject("roMessagePort")
    transfer.SetMessagePort(xferPort)
    if transfer.AsyncPostFromString(body)
        msg = wait(10000, xferPort)
        if type(msg) = "roUrlEvent"
            code = msg.GetResponseCode()
            response = msg.GetString()
        else
            code = -1
            response = ""
        end if
    else
        code = -1
        response = ""
    end if

    print "[MM] PairTask: start response code=" ; str(code).trim() ; " body=" ; response

    if code <> 200 or response = "" or response = invalid
        m.top.pairError = "Failed to start pairing (HTTP " + str(code).trim() + ")"
        return
    end if

    json = parseJSON(response)
    if json = invalid or json.code = invalid
        m.top.pairError = "Invalid response from server"
        return
    end if

    ' Set base URL BEFORE pair code — pairCode observer fires immediately
    if json.base_url <> invalid and json.base_url <> ""
        m.top.pairBaseUrl = json.base_url
        print "[MM] PairTask: canonical base URL from start: " ; json.base_url
    end if
    m.top.pairCode = json.code
    print "[MM] PairTask: pair code = " ; json.code
end sub

' Poll the server for pairing completion
sub doPollStatus()
    serverUrl = m.top.serverUrl
    code = m.top.pairCode

    if serverUrl = "" or code = "" or serverUrl = invalid or code = invalid
        m.top.pairError = "Missing server URL or pair code"
        return
    end if

    url = serverUrl + "/api/pair/status?code=" + code
    maxPolls = 100  ' 5 minutes at 3s intervals

    for i = 0 to maxPolls
        transfer = CreateObject("roUrlTransfer")
        transfer.SetUrl(url)
        transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
        transfer.InitClientCertificates()

        xferPort = CreateObject("roMessagePort")
        transfer.SetMessagePort(xferPort)
        if transfer.AsyncGetToString()
            msg = wait(10000, xferPort)
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

        if httpCode = 404
            print "[MM] PairTask: pair code expired or not found"
            m.top.pairStatus = "expired"
            return
        end if

        if httpCode = 200 and response <> "" and response <> invalid
            json = parseJSON(response)
            if json <> invalid
                if json.status = "paired"
                    print "[MM] PairTask: pairing complete! token received"
                    ' Set token/username/baseUrl BEFORE status — status observer fires immediately
                    m.top.pairToken = json.token
                    m.top.pairUsername = json.username
                    if json.base_url <> invalid and json.base_url <> ""
                        m.top.pairBaseUrl = json.base_url
                        print "[MM] PairTask: canonical base URL: " ; json.base_url
                    end if
                    m.top.pairStatus = "paired"
                    return
                else if json.status = "expired"
                    print "[MM] PairTask: pair code expired"
                    m.top.pairStatus = "expired"
                    return
                end if
            end if
        end if

        ' Wait 3 seconds before polling again
        sleep(3000)
    end for

    print "[MM] PairTask: polling timed out"
    m.top.pairStatus = "expired"
end sub
