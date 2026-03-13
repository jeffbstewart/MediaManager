sub Main(args as dynamic)
    print "[MM] main: starting application v2"

    screen = CreateObject("roSGScreen")
    m.port = CreateObject("roMessagePort")
    screen.setMessagePort(m.port)

    scene = screen.CreateScene("MainScene")
    screen.show()
    print "[MM] main: scene created, screen shown"

    ' Check for deep-link launch args (voice search on cold launch)
    if args <> invalid and args.mediaType <> invalid and args.contentId <> invalid
        print "[MM] main: deep-link launch — mediaType=" ; args.mediaType ; " contentId=" ; args.contentId
        scene.voiceSearchQuery = args.contentId
    end if

    ' Main message loop
    while true
        msg = wait(0, m.port)
        msgType = type(msg)

        if msgType = "roSGScreenEvent"
            if msg.isScreenClosed()
                print "[MM] main: screen closed, exiting"
                return
            end if
        else if msgType = "roInputEvent"
            ' Voice search or ECP input while app is running
            info = msg.getInfo()
            print "[MM] main: roInputEvent received"
            if info <> invalid
                contentId = info.contentId
                mediaType = info.mediaType
                query = info.keyword
                if query <> invalid and query <> ""
                    print "[MM] main: voice search keyword=" ; query
                    scene.voiceSearchQuery = query
                else if contentId <> invalid and contentId <> ""
                    print "[MM] main: voice search contentId=" ; contentId
                    scene.voiceSearchQuery = contentId
                end if
            end if
        end if
    end while
end sub
