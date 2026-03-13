sub init()
    print "[MM] EpisodePickerScreen: init"
    m.posterImage = m.top.findNode("posterImage")
    m.posterFallback = m.top.findNode("posterFallback")
    m.showTitle = m.top.findNode("showTitle")
    m.metaLabel = m.top.findNode("metaLabel")
    m.descLabel = m.top.findNode("descLabel")
    m.castSummary = m.top.findNode("castSummary")
    m.seasonBtnFocus = m.top.findNode("seasonBtnFocus")
    m.seasonBtnLabel = m.top.findNode("seasonBtnLabel")
    m.castHeader = m.top.findNode("castHeader")
    m.similarHeader = m.top.findNode("similarHeader")
    m.similarRow = m.top.findNode("similarRow")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.titleDetailTask = m.top.findNode("titleDetailTask")

    ' Episode virtual list
    m.episodeArea = m.top.findNode("episodeArea")
    m.episodeHighlight = m.top.findNode("episodeHighlight")
    m.EP_VISIBLE = 9
    m.EP_ITEM_H = 44
    m.epSlots = []
    epFont = m.top.findNode("epFontTemplate").font
    for i = 0 to m.EP_VISIBLE - 1
        lbl = m.episodeArea.createChild("Label")
        lbl.translation = [8, i * m.EP_ITEM_H + 8]
        lbl.width = 1784
        lbl.height = m.EP_ITEM_H
        lbl.color = "#c0c0c0"
        lbl.font = epFont
        m.epSlots.push(lbl)
    end for

    ' Cast virtual list
    m.castArea = m.top.findNode("castArea")
    m.castHighlight = m.top.findNode("castHighlight")
    m.CAST_VISIBLE = 3
    m.CAST_ITEM_H = 40
    m.castSlots = []
    castFont = m.top.findNode("castFontTemplate").font
    for i = 0 to m.CAST_VISIBLE - 1
        lbl = m.castArea.createChild("Label")
        lbl.translation = [8, i * m.CAST_ITEM_H + 6]
        lbl.width = 884
        lbl.height = m.CAST_ITEM_H
        lbl.color = "#c0c0c0"
        lbl.font = castFont
        m.castSlots.push(lbl)
    end for

    m.posterImage.observeField("loadStatus", "onPosterLoadStatus")
    m.titleDetailTask.observeField("detailResult", "onDetailResult")
    m.titleDetailTask.observeField("detailError", "onDetailError")
    m.similarRow.observeField("rowItemSelected", "onSimilarSelected")
    m.top.observeField("focusedChild", "onFocusChanged")

    ' Focus state
    m.focusSection = "episodes"
    m.seasonsData = []
    m.currentSeasonIndex = 0
    m.titleDetail = invalid
    m.hasCast = false
    m.hasSimilar = false

    ' Episode list state
    m.epLabels = []          ' all computed label strings
    m.epFocusIdx = 0         ' focused episode index
    m.epScrollTop = 0        ' first visible episode index

    ' Cast list state
    m.castLabels = []
    m.castFocusIdx = 0
    m.castScrollTop = 0
end sub

sub onFocusChanged()
    if m.top.hasFocus()
        print "[MM] EpisodePickerScreen: group received focus, delegating to " ; m.focusSection
        setFocusSection(m.focusSection)
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
    m.loadingLabel.visible = true
    m.episodeArea.visible = false

    ' Show poster immediately from carousel data
    if titleData.posterUrl <> invalid and titleData.posterUrl <> ""
        m.posterImage.uri = titleData.posterUrl
    end if

    ' Fetch title detail from server
    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey
    if serverUrl = invalid or serverUrl = "" or apiKey = invalid or apiKey = ""
        m.loadingLabel.text = "No server credentials"
        return
    end if

    detailUrl = serverUrl + "/roku/title/" + str(titleId).trim() + ".json?key=" + apiKey
    m.titleDetailTask.control = "stop"
    m.titleDetailTask.detailUrl = detailUrl
    m.titleDetailTask.functionName = "doFetch"
    m.titleDetailTask.control = "run"
end sub

sub onDetailResult()
    detail = m.titleDetailTask.detailResult
    if detail = invalid
        m.loadingLabel.text = "Failed to load episodes"
        return
    end if

    m.titleDetail = detail
    m.loadingLabel.visible = false
    m.episodeArea.visible = true

    print "[MM] EpisodePickerScreen: detail loaded — " ; detail.name

    ' Update metadata
    m.showTitle.text = detail.name

    metaParts = []
    if detail.year <> invalid and detail.year > 0
        metaParts.push(str(detail.year).trim())
    end if
    if detail.contentRating <> invalid and detail.contentRating <> ""
        metaParts.push(detail.contentRating)
    end if
    m.metaLabel.text = metaParts.join(" · ")

    if detail.description <> invalid and detail.description <> ""
        m.descLabel.text = detail.description
    end if

    ' Update poster
    if detail.posterUrl <> invalid and detail.posterUrl <> ""
        m.posterImage.uri = detail.posterUrl
    end if

    if detail.seasons = invalid or detail.seasons.count() = 0
        m.loadingLabel.text = "No episodes available"
        m.loadingLabel.visible = true
        return
    end if

    m.seasonsData = detail.seasons

    ' Cast summary (non-interactive text at top)
    if detail.cast <> invalid and detail.cast.count() > 0
        names = []
        for each cm in detail.cast
            if names.count() < 5 then names.push(cm.name)
        end for
        m.castSummary.text = "Cast: " + names.join(", ")
        m.castSummary.visible = true
    end if

    ' Build cast labels
    m.hasCast = false
    if detail.cast <> invalid and detail.cast.count() > 0
        m.hasCast = true
        m.castHeader.visible = true
        m.castArea.visible = true

        m.castLabels = []
        for each cm in detail.cast
            label = cm.name
            if cm.character <> invalid and cm.character <> ""
                label = label + "  as  " + cm.character
            end if
            m.castLabels.push(label)
        end for
        m.castFocusIdx = 0
        m.castScrollTop = 0
        updateCastSlots()
    end if

    ' Build similar titles row
    m.hasSimilar = false
    if detail.similarTitles <> invalid and detail.similarTitles.count() > 0
        m.hasSimilar = true
        m.similarHeader.visible = true
        m.similarRow.visible = true

        rowContent = createObject("roSGNode", "ContentNode")
        rowNode = createObject("roSGNode", "ContentNode")
        for each st in detail.similarTitles
            itemNode = createObject("roSGNode", "ContentNode")
            if st.posterUrl <> invalid and st.posterUrl <> ""
                itemNode.HDPosterUrl = st.posterUrl
            else
                itemNode.HDPosterUrl = "pkg:/images/placeholder-poster.png"
            end if
            itemNode.title = st.name
            itemNode.addFields({ titleId: st.titleId })
            itemNode.addFields({ mediaType: st.mediaType })
            rowNode.appendChild(itemNode)
        end for
        rowContent.appendChild(rowNode)
        m.similarRow.content = rowContent
    end if

    ' Use server-computed next-up to pick initial season and focus
    nextSeasonIdx = 0
    nextEpisodeIdx = 0
    if detail.nextSeasonIndex <> invalid
        nextSeasonIdx = detail.nextSeasonIndex
    end if
    if detail.nextEpisodeIndex <> invalid
        nextEpisodeIdx = detail.nextEpisodeIndex
    end if

    print "[MM] EpisodePickerScreen: next-up -> season " ; str(nextSeasonIdx).trim() ; " episode " ; str(nextEpisodeIdx).trim()

    ' Load the next-up season and focus the episode
    loadEpisodes(nextSeasonIdx)
    m.epFocusIdx = nextEpisodeIdx
    ' Scroll so the focused episode is visible
    if m.epFocusIdx >= m.EP_VISIBLE
        m.epScrollTop = m.epFocusIdx - m.EP_VISIBLE + 1
    else
        m.epScrollTop = 0
    end if
    updateEpisodeSlots()

    m.focusSection = "episodes"
    setFocusSection("episodes")
end sub

sub onDetailError()
    errorMsg = m.titleDetailTask.detailError
    if errorMsg = invalid or errorMsg = "" then return
    print "[MM] EpisodePickerScreen: detail error — " ; errorMsg
    m.loadingLabel.text = "Failed to load: " + errorMsg
end sub

sub onPosterLoadStatus()
    if m.posterImage.loadStatus = "ready"
        m.posterFallback.visible = false
    end if
end sub

' ---- Season / Episode Management ----

sub loadEpisodes(seasonIndex as integer)
    if seasonIndex >= m.seasonsData.count() then return

    season = m.seasonsData[seasonIndex]
    m.currentSeasonIndex = seasonIndex

    seasonNum = str(season.seasonNumber).trim()
    if m.seasonsData.count() > 1
        m.seasonBtnLabel.text = "Season " + seasonNum + " (change)"
    else
        m.seasonBtnLabel.text = "Season " + seasonNum
    end if

    m.epLabels = []
    m.epFocusIdx = 0
    m.epScrollTop = 0

    if season.episodes <> invalid
        for each ep in season.episodes
            epNum = str(ep.episodeNumber).trim()
            epName = ep.name
            if epName = invalid or epName = ""
                epName = "Episode " + epNum
            end if

            ' Build label — status as suffix to keep alignment
            label = "E" + epNum + "  " + epName
            if ep.quality <> invalid and ep.quality <> ""
                label = label + "  [" + ep.quality + "]"
            end if
            if ep.watchedPercent <> invalid and ep.watchedPercent >= 90
                label = label + "  (watched)"
            else if ep.resumePosition <> invalid and ep.resumePosition > 0
                mins = int(ep.resumePosition / 60)
                label = label + "  (resume " + str(mins).trim() + "m)"
            end if

            m.epLabels.push(label)
        end for
    end if

    updateEpisodeSlots()
    print "[MM] EpisodePickerScreen: loadEpisodes — season=" ; seasonNum ; " episodes=" ; str(m.epLabels.count()).trim()
end sub

sub updateEpisodeSlots()
    for i = 0 to m.EP_VISIBLE - 1
        dataIdx = m.epScrollTop + i
        if dataIdx < m.epLabels.count()
            m.epSlots[i].text = m.epLabels[dataIdx]
            m.epSlots[i].visible = true
            ' Highlight focused item with brighter color
            if dataIdx = m.epFocusIdx and m.focusSection = "episodes"
                m.epSlots[i].color = "#ffffff"
            else
                m.epSlots[i].color = "#c0c0c0"
            end if
        else
            m.epSlots[i].text = ""
            m.epSlots[i].visible = false
        end if
    end for

    ' Position highlight
    if m.focusSection = "episodes" and m.epLabels.count() > 0
        visibleRow = m.epFocusIdx - m.epScrollTop
        m.episodeHighlight.translation = [0, visibleRow * m.EP_ITEM_H]
        m.episodeHighlight.visible = true
    else
        m.episodeHighlight.visible = false
    end if
end sub

sub updateCastSlots()
    for i = 0 to m.CAST_VISIBLE - 1
        dataIdx = m.castScrollTop + i
        if dataIdx < m.castLabels.count()
            m.castSlots[i].text = m.castLabels[dataIdx]
            m.castSlots[i].visible = true
            if dataIdx = m.castFocusIdx and m.focusSection = "cast"
                m.castSlots[i].color = "#ffffff"
            else
                m.castSlots[i].color = "#c0c0c0"
            end if
        else
            m.castSlots[i].text = ""
            m.castSlots[i].visible = false
        end if
    end for

    ' Position highlight
    if m.focusSection = "cast" and m.castLabels.count() > 0
        visibleRow = m.castFocusIdx - m.castScrollTop
        m.castHighlight.translation = [0, visibleRow * m.CAST_ITEM_H]
        m.castHighlight.visible = true
    else
        m.castHighlight.visible = false
    end if
end sub

sub showSeasonDialog()
    if m.seasonsData.count() <= 1 then return

    dialog = createObject("roSGNode", "StandardMessageDialog")
    dialog.title = "Select Season"

    buttons = []
    for each season in m.seasonsData
        buttons.push("Season " + str(season.seasonNumber).trim())
    end for
    dialog.buttons = buttons
    dialog.observeField("buttonSelected", "onSeasonDialogButton")
    m.top.getScene().dialog = dialog
end sub

sub onSeasonDialogButton()
    dialog = m.top.getScene().dialog
    if dialog = invalid then return

    seasonIdx = dialog.buttonSelected
    m.top.getScene().dialog = invalid

    if seasonIdx >= 0 and seasonIdx < m.seasonsData.count()
        print "[MM] EpisodePickerScreen: season selected from dialog — index=" ; str(seasonIdx).trim()
        loadEpisodes(seasonIdx)
    end if

    setFocusSection("episodes")
end sub

' ---- Selection Handlers ----

sub onEpisodeChosen()
    epIndex = m.epFocusIdx
    if epIndex < 0 then return

    if m.currentSeasonIndex >= m.seasonsData.count() then return
    season = m.seasonsData[m.currentSeasonIndex]
    if season.episodes = invalid or epIndex >= season.episodes.count() then return

    ep = season.episodes[epIndex]
    print "[MM] EpisodePickerScreen: episode selected — S" ; str(season.seasonNumber).trim() ; "E" ; str(ep.episodeNumber).trim() ; " " ; ep.name

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

sub onCastChosen()
    idx = m.castFocusIdx
    if idx < 0 or m.titleDetail = invalid then return
    if m.titleDetail.cast = invalid or idx >= m.titleDetail.cast.count() then return

    cm = m.titleDetail.cast[idx]
    print "[MM] EpisodePickerScreen: cast selected — " ; cm.name ; " (person " ; str(cm.tmdbPersonId).trim() ; ")"
    m.top.actorSelected = {
        tmdbPersonId: cm.tmdbPersonId,
        name: cm.name
    }
end sub

sub onSimilarSelected()
    selected = m.similarRow.rowItemSelected
    if selected = invalid or m.titleDetail = invalid then return

    itemIndex = selected[1]
    if m.titleDetail.similarTitles = invalid or itemIndex >= m.titleDetail.similarTitles.count() then return

    st = m.titleDetail.similarTitles[itemIndex]
    print "[MM] EpisodePickerScreen: similar title selected — " ; st.name ; " (titleId=" ; str(st.titleId).trim() ; ")"
    m.top.titleSelected = st
end sub

' ---- Focus Management ----

sub setFocusSection(section as string)
    m.focusSection = section
    m.seasonBtnFocus.visible = false
    m.episodeHighlight.visible = false
    m.castHighlight.visible = false

    if section = "season"
        m.seasonBtnFocus.visible = true
        ' Keep focus on this Group — don't give it to children
        m.top.setFocus(true)
    else if section = "episodes"
        m.top.setFocus(true)
        updateEpisodeSlots()
    else if section = "cast"
        m.top.setFocus(true)
        updateCastSlots()
    else if section = "similar"
        m.similarRow.setFocus(true)
    end if
end sub

' ---- Key Handling ----

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "OK"
        if m.focusSection = "season"
            showSeasonDialog()
            return true
        else if m.focusSection = "episodes"
            onEpisodeChosen()
            return true
        else if m.focusSection = "cast"
            onCastChosen()
            return true
        end if
    end if

    if key = "up"
        if m.focusSection = "season"
            return true
        else if m.focusSection = "episodes"
            if m.epFocusIdx > 0
                m.epFocusIdx--
                if m.epFocusIdx < m.epScrollTop
                    m.epScrollTop = m.epFocusIdx
                end if
                updateEpisodeSlots()
            else
                setFocusSection("season")
            end if
            return true
        else if m.focusSection = "cast"
            if m.castFocusIdx > 0
                m.castFocusIdx--
                if m.castFocusIdx < m.castScrollTop
                    m.castScrollTop = m.castFocusIdx
                end if
                updateCastSlots()
            else
                setFocusSection("episodes")
            end if
            return true
        else if m.focusSection = "similar"
            if m.hasCast
                setFocusSection("cast")
            else
                setFocusSection("episodes")
            end if
            return true
        end if
    end if

    if key = "down"
        if m.focusSection = "season"
            setFocusSection("episodes")
            return true
        else if m.focusSection = "episodes"
            if m.epFocusIdx < m.epLabels.count() - 1
                m.epFocusIdx++
                if m.epFocusIdx >= m.epScrollTop + m.EP_VISIBLE
                    m.epScrollTop = m.epFocusIdx - m.EP_VISIBLE + 1
                end if
                updateEpisodeSlots()
            else
                ' At bottom of episodes — move to next section
                if m.hasCast
                    setFocusSection("cast")
                else if m.hasSimilar
                    setFocusSection("similar")
                end if
            end if
            return true
        else if m.focusSection = "cast"
            if m.castFocusIdx < m.castLabels.count() - 1
                m.castFocusIdx++
                if m.castFocusIdx >= m.castScrollTop + m.CAST_VISIBLE
                    m.castScrollTop = m.castFocusIdx - m.CAST_VISIBLE + 1
                end if
                updateCastSlots()
            else
                if m.hasSimilar
                    setFocusSection("similar")
                end if
            end if
            return true
        end if
    end if

    return false
end function
