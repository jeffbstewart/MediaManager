sub init()
    m.poster = m.top.findNode("poster")
    m.titleLabel = m.top.findNode("titleLabel")
end sub

sub onContentChanged()
    item = m.top.itemContent
    if item = invalid then return

    posterUrl = item.HDPosterUrl
    if posterUrl <> invalid and posterUrl <> ""
        m.poster.uri = posterUrl
    else
        m.poster.uri = "pkg:/images/placeholder-poster.png"
    end if

    m.titleLabel.text = item.title
end sub
