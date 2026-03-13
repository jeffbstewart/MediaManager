sub init()
    print "[MM] EpisodePickerScreen: init"
    m.showTitle = m.top.findNode("showTitle")
    m.seasonList = m.top.findNode("seasonList")
    m.episodeList = m.top.findNode("episodeList")
    m.episodeHeader = m.top.findNode("episodeHeader")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.titleDetailTask = m.top.findNode("titleDetailTask")

    m.seasonList.observeField("itemFocused", "onSeasonFocused")
    m.episodeList.observeField("itemSelected", "onEpisodeSelected")
    m.titleDetailTask.observeField("detailResult", "onDetailResult")
    m.titleDetailTask.observeField("detailError", "onDetailError")
    m.top.observeField("focusedChild", "onFocusChanged")

    ' Track which list last had focus
    m.lastFocusedChild = "episodes"

    ' Store season/episode data from server
    m.seasonsData = []
    m.currentSeasonIndex = 0
    m.titleDetail = invalid
end sub

sub onFocusChanged()
    if m.top.hasFocus()
        print "[MM] EpisodePickerScreen: group received focus, delegating to " ; m.lastFocusedChild
        if m.lastFocusedChild = "seasons"
            m.seasonList.setFocus(true)
        else
            m.episodeList.setFocus(true)
        end if
    end if
end sub

sub onTitleDataChanged()
    titleData = m.top.titleData
    if titleData = invalid then return

    titleId = titleData.titleId
    titleName = titleData.name
    if titleName = invalid then titleName = "Unknown"

    print "[MM] EpisodePickerScreen: loading title detail for " ; titleName ; " (id=" ; str(titleId).trim() ; ")"

    m.showTitle.text = titleName

    ' Show loading state
    m.loadingLabel.visible = true
    m.seasonList.visible = false
    m.episodeList.visible = false

    ' Fetch title detail from server
    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey
    if serverUrl = invalid or serverUrl = "" or apiKey = invalid or apiKey = ""
        print "[MM] EpisodePickerScreen: no server credentials"
        m.loadingLabel.text = "No server credentials"
        return
    end if

    detailUrl = serverUrl + "/roku/title/" + str(titleId).trim() + ".json?key=" + apiKey
    print "[MM] EpisodePickerScreen: fetching " ; detailUrl

    m.titleDetailTask.control = "stop"
    m.titleDetailTask.detailUrl = detailUrl
    m.titleDetailTask.functionName = "doFetch"
    m.titleDetailTask.control = "run"
end sub

sub onDetailResult()
    detail = m.titleDetailTask.detailResult
    if detail = invalid
        print "[MM] EpisodePickerScreen: detail result invalid"
        m.loadingLabel.text = "Failed to load episodes"
        return
    end if

    m.titleDetail = detail
    m.loadingLabel.visible = false
    m.seasonList.visible = true
    m.episodeList.visible = true

    print "[MM] EpisodePickerScreen: detail loaded — " ; detail.name

    if detail.seasons = invalid or detail.seasons.count() = 0
        print "[MM] EpisodePickerScreen: no seasons found"
        m.loadingLabel.text = "No episodes available"
        m.loadingLabel.visible = true
        return
    end if

    ' Store seasons data
    m.seasonsData = detail.seasons

    ' Build season list
    seasonContent = createObject("roSGNode", "ContentNode")
    for each season in detail.seasons
        item = createObject("roSGNode", "ContentNode")
        item.title = "Season " + str(season.seasonNumber).trim()
        seasonContent.appendChild(item)
    end for
    m.seasonList.content = seasonContent

    ' Focus season list and load first season
    m.lastFocusedChild = "seasons"
    m.seasonList.setFocus(true)
    if m.seasonsData.count() > 0
        loadEpisodes(0)
    end if
end sub

sub onDetailError()
    errorMsg = m.titleDetailTask.detailError
    if errorMsg = invalid or errorMsg = "" then return

    print "[MM] EpisodePickerScreen: detail error — " ; errorMsg
    m.loadingLabel.text = "Failed to load: " + errorMsg
end sub

sub onSeasonFocused()
    index = m.seasonList.itemFocused
    if index >= 0 and index < m.seasonsData.count()
        print "[MM] EpisodePickerScreen: season focused — index=" ; str(index).trim()
        loadEpisodes(index)
    end if
end sub

sub loadEpisodes(seasonIndex as integer)
    if seasonIndex >= m.seasonsData.count() then return

    season = m.seasonsData[seasonIndex]
    m.currentSeasonIndex = seasonIndex

    seasonNum = str(season.seasonNumber).trim()
    m.episodeHeader.text = "Season " + seasonNum + " Episodes"

    ' Build episode list
    episodeContent = createObject("roSGNode", "ContentNode")

    if season.episodes <> invalid
        for each ep in season.episodes
            item = createObject("roSGNode", "ContentNode")
            epNum = str(ep.episodeNumber).trim()
            epName = ep.name
            if epName = invalid or epName = ""
                epName = "Episode " + epNum
            end if

            label = "E" + epNum + " - " + epName
            if ep.quality <> invalid and ep.quality <> ""
                label = label + "  [" + ep.quality + "]"
            end if
            if ep.resumePosition <> invalid and ep.resumePosition > 0
                mins = int(ep.resumePosition / 60)
                label = label + "  (resume " + str(mins).trim() + "m)"
            end if

            item.title = label
            episodeContent.appendChild(item)
        end for
    end if

    m.episodeList.content = episodeContent
    print "[MM] EpisodePickerScreen: loadEpisodes — season=" ; seasonNum ; " episodes=" ; str(episodeContent.getChildCount()).trim()
end sub

sub onEpisodeSelected()
    epIndex = m.episodeList.itemSelected
    if epIndex < 0 then return

    if m.currentSeasonIndex >= m.seasonsData.count() then return
    season = m.seasonsData[m.currentSeasonIndex]
    if season.episodes = invalid or epIndex >= season.episodes.count() then return

    ep = season.episodes[epIndex]
    print "[MM] EpisodePickerScreen: episode selected — S" ; str(season.seasonNumber).trim() ; "E" ; str(ep.episodeNumber).trim() ; " " ; ep.name

    ' Build play data matching what HomeScreen sends for movies
    playData = {
        name: m.titleDetail.name + " - S" + str(season.seasonNumber).trim() + "E" + str(ep.episodeNumber).trim() + " " + ep.name,
        showName: m.titleDetail.name,
        titleId: m.titleDetail.titleId,
        transcodeId: ep.transcodeId,
        streamUrl: ep.streamUrl,
        subtitleUrl: ep.subtitleUrl,
        bifUrl: ep.bifUrl,
        quality: ep.quality,
        resumePosition: ep.resumePosition,
        year: m.titleDetail.year,
        contentRating: m.titleDetail.contentRating,
        mediaType: "TV",
        seasonsData: m.seasonsData,
        seasonIndex: m.currentSeasonIndex,
        episodeIndex: epIndex
    }

    m.top.episodeSelected = playData
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "right"
        if m.seasonList.hasFocus()
            print "[MM] EpisodePickerScreen: focus — seasons -> episodes"
            m.lastFocusedChild = "episodes"
            m.episodeList.setFocus(true)
            return true
        end if
    end if

    if key = "left"
        if m.episodeList.hasFocus()
            print "[MM] EpisodePickerScreen: focus — episodes -> seasons"
            m.lastFocusedChild = "seasons"
            m.seasonList.setFocus(true)
            return true
        end if
    end if

    return false
end function
