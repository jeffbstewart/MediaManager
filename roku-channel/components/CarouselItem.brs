sub init()
    m.posterImage = m.top.findNode("posterImage")
    m.fallbackBg = m.top.findNode("fallbackBg")
    m.wishBadge = m.top.findNode("wishBadge")
    m.titleLabel = m.top.findNode("titleLabel")
    m.posterImage.observeField("loadStatus", "onPosterLoadStatus")
end sub

sub onContentChanged()
    itemData = m.top.itemContent
    if itemData = invalid then return

    posterUri = itemData.HDPosterUrl
    if posterUri <> invalid and posterUri <> ""
        m.posterImage.uri = posterUri
    end if

    ' Set title label
    if itemData.title <> invalid and itemData.title <> ""
        m.titleLabel.text = itemData.title
    else
        m.titleLabel.text = ""
    end if

    ' Show wish-fulfilled badge if the item has the field set
    if itemData.hasField("wishFulfilled") and itemData.wishFulfilled = true
        m.wishBadge.visible = true
    else
        m.wishBadge.visible = false
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
