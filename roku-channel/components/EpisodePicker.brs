sub init()
    print "[MM] EpisodePicker: init"
    m.showTitle = m.top.findNode("showTitle")
    m.seasonList = m.top.findNode("seasonList")
    m.episodeList = m.top.findNode("episodeList")
    m.episodeHeader = m.top.findNode("episodeHeader")

    m.seasonList.observeField("itemFocused", "onSeasonFocused")
    m.episodeList.observeField("itemSelected", "onEpisodeSelected")
    m.top.observeField("focusedChild", "onFocusChanged")

    ' Track which child last had focus for restoring after video playback
    m.lastFocusedChild = "episodes"

    ' Store seasons data for lookup
    m.seasonsData = []
end sub

sub onFocusChanged()
    ' When the EpisodePicker Group itself receives focus (e.g., returning from video),
    ' delegate to the last focused child list so remote input works
    if m.top.hasFocus()
        print "[MM] EpisodePicker: group received focus, delegating to " ; m.lastFocusedChild
        if m.lastFocusedChild = "seasons"
            m.seasonList.setFocus(true)
        else
            m.episodeList.setFocus(true)
        end if
    end if
end sub

sub onSeriesContentSet()
    series = m.top.seriesContent
    if series = invalid then return

    m.showTitle.text = series.title
    print "[MM] EpisodePicker: loaded series — title=" ; series.title ; " seasons=" ; str(series.getChildCount()).trim()

    ' Build season list from series children (seasons)
    m.seasonsData = []
    seasonContent = createObject("roSGNode", "ContentNode")

    for i = 0 to series.getChildCount() - 1
        season = series.getChild(i)
        m.seasonsData.push(season)

        item = createObject("roSGNode", "ContentNode")
        if season.hasField("seasonNumber") and season.seasonNumber <> invalid
            item.title = "Season " + season.seasonNumber
        else
            item.title = "Season " + str(i + 1).trim()
        end if
        seasonContent.appendChild(item)
    end for

    m.seasonList.content = seasonContent

    ' Focus season list and load first season's episodes
    m.seasonList.setFocus(true)
    if m.seasonsData.count() > 0
        loadEpisodes(0)
    end if
end sub

sub onSeasonFocused()
    index = m.seasonList.itemFocused
    if index >= 0 and index < m.seasonsData.count()
        print "[MM] EpisodePicker: season focused — index=" ; str(index).trim()
        loadEpisodes(index)
    end if
end sub

sub loadEpisodes(seasonIndex as integer)
    season = m.seasonsData[seasonIndex]
    if season = invalid then return

    seasonNum = ""
    if season.hasField("seasonNumber") and season.seasonNumber <> invalid
        seasonNum = season.seasonNumber
    end if
    m.episodeHeader.text = "Season " + seasonNum + " Episodes"

    ' Build episode list
    episodeContent = createObject("roSGNode", "ContentNode")

    for i = 0 to season.getChildCount() - 1
        ep = season.getChild(i)
        item = createObject("roSGNode", "ContentNode")

        epNum = ""
        if ep.hasField("episodeNumber") and ep.episodeNumber <> invalid
            epNum = ep.episodeNumber
        end if

        epTitle = ep.title
        if epTitle = invalid or epTitle = ""
            epTitle = "Episode " + epNum
        end if

        item.title = "E" + epNum + " - " + epTitle
        episodeContent.appendChild(item)
    end for

    m.episodeList.content = episodeContent
    print "[MM] EpisodePicker: loadEpisodes — season=" ; seasonNum ; " episodes=" ; str(season.getChildCount()).trim()

    ' Store current season index for episode selection
    m.currentSeasonIndex = seasonIndex
end sub

sub onEpisodeSelected()
    epIndex = m.episodeList.itemSelected
    if epIndex < 0 then return

    season = m.seasonsData[m.currentSeasonIndex]
    if season = invalid then return

    ep = season.getChild(epIndex)
    if ep = invalid then return

    print "[MM] EpisodePicker: episode selected — title=" ; ep.title ; " id=" ; ep.id

    ' Build video content node
    videoNode = createObject("roSGNode", "ContentNode")
    videoNode.title = ep.title
    videoNode.id = ep.id

    if ep.hasField("streamUrl") and ep.streamUrl <> invalid
        videoNode.url = ep.streamUrl
        print "[MM] EpisodePicker: streamUrl=" ; ep.streamUrl
    else
        print "[MM] EpisodePicker: WARNING: no streamUrl for episode"
    end if

    videoNode.streamFormat = "mp4"

    ' Subtitle URL
    videoNode.addField("subtitleUrl", "string", false)
    if ep.hasField("subtitleUrl") and ep.subtitleUrl <> invalid and ep.subtitleUrl <> ""
        videoNode.subtitleUrl = ep.subtitleUrl
        print "[MM] EpisodePicker: subtitleUrl=" ; ep.subtitleUrl
    end if

    ' Server-side playback position
    videoNode.addField("playbackPosition", "integer", false)
    if ep.hasField("playbackPosition") and ep.playbackPosition <> invalid and ep.playbackPosition > 0
        videoNode.playbackPosition = ep.playbackPosition
        print "[MM] EpisodePicker: playbackPosition=" ; str(ep.playbackPosition).trim()
    end if

    m.top.videoContent = videoNode
    m.top.episodeSelected = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "right"
        if m.seasonList.hasFocus()
            print "[MM] EpisodePicker: focus — seasons -> episodes"
            m.lastFocusedChild = "episodes"
            m.episodeList.setFocus(true)
            return true
        end if
    end if

    if key = "left"
        if m.episodeList.hasFocus()
            print "[MM] EpisodePicker: focus — episodes -> seasons"
            m.lastFocusedChild = "seasons"
            m.seasonList.setFocus(true)
            return true
        end if
    end if

    return false
end function
