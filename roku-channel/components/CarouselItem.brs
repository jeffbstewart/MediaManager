sub init()
    m.posterImage = m.top.findNode("posterImage")
    m.fallbackBg = m.top.findNode("fallbackBg")
    m.posterImage.observeField("loadStatus", "onPosterLoadStatus")
end sub

sub onContentChanged()
    itemData = m.top.itemContent
    if itemData = invalid then return

    posterUri = itemData.HDPosterUrl
    if posterUri <> invalid and posterUri <> ""
        m.posterImage.uri = posterUri
    end if
end sub

sub onPosterLoadStatus()
    status = m.posterImage.loadStatus
    if status = "failed"
        print "[MM] CarouselItem: poster FAILED to load: " ; m.posterImage.uri
    else if status = "ready"
        m.fallbackBg.visible = false
    end if
end sub
