sub init()
    print "[MM] MovieDetailScreen: init"
    m.posterImage = m.top.findNode("posterImage")
    m.posterFallback = m.top.findNode("posterFallback")
    m.titleLabel = m.top.findNode("titleLabel")
    m.metaLabel = m.top.findNode("metaLabel")
    m.descLabel = m.top.findNode("descLabel")
    m.playBtnFocus = m.top.findNode("playBtnFocus")
    m.playBtnLabel = m.top.findNode("playBtnLabel")
    m.castHeader = m.top.findNode("castHeader")
    m.similarHeader = m.top.findNode("similarHeader")
    m.similarRow = m.top.findNode("similarRow")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.titleDetailTask = m.top.findNode("titleDetailTask")

    ' Cast virtual list
    m.castArea = m.top.findNode("castArea")
    m.castHighlight = m.top.findNode("castHighlight")
    m.CAST_VISIBLE = 5
    m.CAST_ITEM_H = 44
    m.castSlots = []
    castFont = m.top.findNode("castFontTemplate").font
    for i = 0 to m.CAST_VISIBLE - 1
        lbl = m.castArea.createChild("Label")
        lbl.translation = [8, i * m.CAST_ITEM_H + 8]
        lbl.width = 784
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

    ' Focus state: "play", "cast", "similar"
    m.focusSection = "play"
    m.titleDetail = invalid
    m.hasCast = false
    m.hasSimilar = false

    ' Cast list state
    m.castLabels = []
    m.castFocusIdx = 0
    m.castScrollTop = 0
end sub

sub onFocusChanged()
    if m.top.hasFocus()
        setFocusSection(m.focusSection)
    end if
end sub

sub onTitleDataChanged()
    titleData = m.top.titleData
    if titleData = invalid then return

    titleId = titleData.titleId
    titleName = titleData.name
    if titleName = invalid then titleName = "Unknown"

    print "[MM] MovieDetailScreen: loading detail for " ; titleName ; " (id=" ; str(titleId).trim() ; ")"

    m.titleLabel.text = titleName
    m.loadingLabel.visible = true

    ' Show poster immediately from carousel data
    if titleData.posterUrl <> invalid and titleData.posterUrl <> ""
        m.posterImage.uri = titleData.posterUrl
    end if

    ' Fetch full detail from server
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
        m.loadingLabel.text = "Failed to load details"
        return
    end if

    m.titleDetail = detail
    m.loadingLabel.visible = false

    print "[MM] MovieDetailScreen: detail loaded — " ; detail.name

    ' Update metadata
    m.titleLabel.text = detail.name

    metaParts = []
    if detail.year <> invalid and detail.year > 0
        metaParts.push(str(detail.year).trim())
    end if
    if detail.contentRating <> invalid and detail.contentRating <> ""
        metaParts.push(detail.contentRating)
    end if
    if detail.quality <> invalid and detail.quality <> ""
        metaParts.push(detail.quality)
    end if
    if metaParts.count() > 0
        m.metaLabel.text = metaParts.join(" · ")
    end if

    if detail.description <> invalid and detail.description <> ""
        m.descLabel.text = detail.description
    end if

    ' Update play button label with resume info
    if detail.resumePosition <> invalid and detail.resumePosition > 30
        mins = int(detail.resumePosition / 60)
        m.playBtnLabel.text = "Resume (" + str(mins).trim() + "m)"
    else if detail.watchedPercent <> invalid and detail.watchedPercent >= 90
        m.playBtnLabel.text = "Play Again"
    end if

    ' Update poster
    if detail.posterUrl <> invalid and detail.posterUrl <> ""
        m.posterImage.uri = detail.posterUrl
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

        ' Adjust vertical position based on cast visibility
        if m.hasCast
            m.similarHeader.translation = [60, 730]
            m.similarRow.translation = [60, 780]
        else
            m.similarHeader.translation = [60, 440]
            m.similarRow.translation = [60, 490]
        end if

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

    setFocusSection("play")
end sub

sub onDetailError()
    errorMsg = m.titleDetailTask.detailError
    if errorMsg = invalid then return
    print "[MM] MovieDetailScreen: detail error — " ; errorMsg
    m.loadingLabel.text = "Failed to load: " + errorMsg
end sub

sub onPosterLoadStatus()
    if m.posterImage.loadStatus = "ready"
        m.posterFallback.visible = false
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

' ---- Focus Management ----

sub setFocusSection(section as string)
    m.focusSection = section
    m.playBtnFocus.visible = false
    m.castHighlight.visible = false

    if section = "play"
        m.playBtnFocus.visible = true
        m.top.setFocus(true)
    else if section = "cast"
        m.top.setFocus(true)
        updateCastSlots()
    else if section = "similar"
        m.similarRow.setFocus(true)
    end if
end sub

' ---- Selection Handlers ----

sub onCastChosen()
    idx = m.castFocusIdx
    if idx < 0 or m.titleDetail = invalid then return
    if m.titleDetail.cast = invalid or idx >= m.titleDetail.cast.count() then return

    cm = m.titleDetail.cast[idx]
    print "[MM] MovieDetailScreen: cast selected — " ; cm.name ; " (person " ; str(cm.tmdbPersonId).trim() ; ")"
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
    print "[MM] MovieDetailScreen: similar title selected — " ; st.name ; " (titleId=" ; str(st.titleId).trim() ; ")"
    m.top.titleSelected = st
end sub

' ---- Key Handling ----

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "OK"
        if m.focusSection = "play" and m.titleDetail <> invalid
            print "[MM] MovieDetailScreen: play pressed"
            m.top.playRequested = m.titleDetail
            return true
        else if m.focusSection = "cast"
            onCastChosen()
            return true
        end if
    end if

    if key = "down"
        if m.focusSection = "play"
            if m.hasCast
                setFocusSection("cast")
            else if m.hasSimilar
                setFocusSection("similar")
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

    if key = "up"
        if m.focusSection = "similar"
            if m.hasCast
                setFocusSection("cast")
            else
                setFocusSection("play")
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
                setFocusSection("play")
            end if
            return true
        else if m.focusSection = "play"
            return true
        end if
    end if

    return false
end function
