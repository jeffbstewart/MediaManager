sub Main(args as dynamic)
    print "[MM] main: starting application"

    screen = CreateObject("roSGScreen")
    m.port = CreateObject("roMessagePort")
    screen.setMessagePort(m.port)

    scene = screen.CreateScene("MainScene")
    screen.show()
    print "[MM] main: scene created, screen shown"

    ' Main message loop
    while true
        msg = wait(0, m.port)
        msgType = type(msg)

        if msgType = "roSGScreenEvent"
            if msg.isScreenClosed()
                print "[MM] main: screen closed, exiting"
                return
            end if
        end if
    end while
end sub
