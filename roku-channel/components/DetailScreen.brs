sub init()
    print "[MM] DetailScreen: init"
    m.poster = m.top.findNode("poster")
    m.titleLabel = m.top.findNode("titleLabel")
    m.metaLabel = m.top.findNode("metaLabel")
    m.castLabel = m.top.findNode("castLabel")
    m.descLabel = m.top.findNode("descLabel")
    m.buttonGroup = m.top.findNode("buttonGroup")

    m.buttonGroup.observeField("buttonSelected", "onButtonSelected")

    m.itemType = ""
end sub

sub onItemContentSet()
    item = m.top.itemContent
    if item = invalid then return

    ' Poster
    posterUrl = item.HDPosterUrl
    if posterUrl <> invalid and posterUrl <> ""
        m.poster.uri = posterUrl
    else
        m.poster.uri = "pkg:/images/placeholder-poster.png"
    end if

    ' Title
    m.titleLabel.text = item.title

    ' Year + Genres
    metaParts = []
    if item.hasField("releaseDate") and item.releaseDate <> invalid and item.releaseDate <> ""
        ' Extract year from "YYYY-MM-DD"
        year = left(item.releaseDate, 4)
        if year <> "2000"
            metaParts.push(year)
        end if
    end if

    if item.hasField("genres") and item.genres <> invalid
        genreStr = joinArray(item.genres, ", ")
        if genreStr <> ""
            metaParts.push(genreStr)
        end if
    end if

    m.metaLabel.text = joinArray(metaParts, "  |  ")

    ' Cast
    if item.hasField("cast") and item.cast <> invalid and item.cast.count() > 0
        m.castLabel.text = "Cast: " + joinArray(item.cast, ", ")
        m.castLabel.visible = true
    else
        m.castLabel.visible = false
    end if

    ' Description
    if item.description <> invalid and item.description <> ""
        m.descLabel.text = item.description
        m.descLabel.visible = true
    else
        m.descLabel.visible = false
    end if

    ' Buttons based on type
    m.itemType = ""
    if item.hasField("itemType")
        m.itemType = item.itemType
    end if

    if m.itemType = "series"
        m.buttonGroup.buttons = ["Episodes"]
    else
        m.buttonGroup.buttons = ["Play"]
    end if

    print "[MM] DetailScreen: loaded — title=" ; item.title ; " type=" ; m.itemType
    m.buttonGroup.setFocus(true)
end sub

sub onButtonSelected()
    if m.itemType = "series"
        print "[MM] DetailScreen: Episodes button pressed"
        m.top.episodesRequested = true
    else
        ' Build video content node for playback
        item = m.top.itemContent
        if item = invalid then return

        videoNode = createObject("roSGNode", "ContentNode")
        videoNode.title = item.title
        videoNode.id = item.id

        if item.hasField("streamUrl") and item.streamUrl <> invalid
            videoNode.url = item.streamUrl
            print "[MM] DetailScreen: Play button — streamUrl=" ; item.streamUrl
        else
            print "[MM] DetailScreen: Play button — WARNING: no streamUrl"
        end if

        videoNode.streamFormat = "mp4"

        ' Subtitle URL
        videoNode.addField("subtitleUrl", "string", false)
        if item.hasField("subtitleUrl") and item.subtitleUrl <> invalid and item.subtitleUrl <> ""
            videoNode.subtitleUrl = item.subtitleUrl
            print "[MM] DetailScreen: subtitleUrl=" ; item.subtitleUrl
        end if

        ' Server-side playback position
        videoNode.addField("playbackPosition", "integer", false)
        if item.hasField("playbackPosition") and item.playbackPosition <> invalid and item.playbackPosition > 0
            videoNode.playbackPosition = item.playbackPosition
            print "[MM] DetailScreen: playbackPosition=" ; str(item.playbackPosition).trim()
        end if

        m.top.videoContent = videoNode
        m.top.playRequested = videoNode
    end if
end sub

function joinArray(arr as object, separator as string) as string
    result = ""
    for i = 0 to arr.count() - 1
        if i > 0
            result = result + separator
        end if
        result = result + arr[i]
    end for
    return result
end function

function onKeyEvent(key as string, press as boolean) as boolean
    return false
end function
