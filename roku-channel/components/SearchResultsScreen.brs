sub init()
    print "[MM] SearchResultsScreen: init"
    m.headerLabel = m.top.findNode("headerLabel")
    m.chipRow = m.top.findNode("chipRow")
    m.resultsRowList = m.top.findNode("resultsRowList")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.noResultsLabel = m.top.findNode("noResultsLabel")
    m.searchTask = m.top.findNode("searchTask")

    m.searchTask.observeField("searchResult", "onSearchResult")
    m.searchTask.observeField("searchError", "onSearchError")
    m.resultsRowList.observeField("rowItemSelected", "onResultSelected")

    m.top.observeField("focusedChild", "onFocusChanged")

    m.allResults = []
    m.filteredResults = []
    m.chipCategories = []  ' category filter keys
    m.chipLabels = []      ' chip Label nodes
    m.chipIndex = 0        ' currently focused chip
    m.focusTarget = "chips"  ' "chips" or "grid"
end sub

sub onFocusChanged()
    if m.top.hasFocus()
        setFocusTarget(m.focusTarget)
    end if
end sub

sub onSearchQueryChanged()
    query = m.top.searchQuery
    if query = invalid or query = "" then return

    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey
    if serverUrl = invalid or serverUrl = "" then return
    if apiKey = invalid or apiKey = "" then return

    print "[MM] SearchResultsScreen: searching for '" ; query ; "'"

    m.headerLabel.text = "Search: " + query
    m.loadingLabel.visible = true
    m.noResultsLabel.visible = false
    m.resultsRowList.visible = false

    ' URL-encode the query
    encodedQuery = encodeUrl(query)
    searchUrl = serverUrl + "/roku/search.json?key=" + apiKey + "&q=" + encodedQuery

    m.searchTask.control = "stop"
    m.searchTask.searchUrl = searchUrl
    m.searchTask.functionName = "doFetch"
    m.searchTask.control = "run"
end sub

function encodeUrl(text as string) as string
    result = ""
    for i = 0 to len(text) - 1
        ch = mid(text, i + 1, 1)
        if ch = " "
            result = result + "%20"
        else if ch = "&"
            result = result + "%26"
        else if ch = "="
            result = result + "%3D"
        else if ch = "?"
            result = result + "%3F"
        else if ch = "#"
            result = result + "%23"
        else
            result = result + ch
        end if
    end for
    return result
end function

sub onSearchResult()
    data = m.searchTask.searchResult
    if data = invalid then return

    m.loadingLabel.visible = false
    m.allResults = data.results
    counts = data.counts

    if m.allResults.count() = 0
        m.noResultsLabel.visible = true
        return
    end if

    ' Build chip labels
    buildChips(counts)

    ' Show all results initially
    m.chipIndex = 0
    filterResults("all")
    m.resultsRowList.visible = true
    setFocusTarget("chips")
end sub

sub onSearchError()
    errorMsg = m.searchTask.searchError
    if errorMsg = invalid or errorMsg = "" then return

    print "[MM] SearchResultsScreen: search error — " ; errorMsg
    m.loadingLabel.visible = false
    m.noResultsLabel.text = "Search failed"
    m.noResultsLabel.visible = true
end sub

sub buildChips(counts as object)
    ' Remove old chip labels
    while m.chipRow.getChildCount() > 0
        m.chipRow.removeChildIndex(0)
    end while
    m.chipLabels = []
    m.chipCategories = []

    chipDefs = [
        { key: "all", label: "All" },
        { key: "movie", label: "Movies" },
        { key: "series", label: "TV Shows" },
        { key: "collection", label: "Collections" },
        { key: "tag", label: "Tags" },
        { key: "genre", label: "Genres" },
        { key: "actor", label: "Actors" }
    ]

    xPos = 0
    for each chipDef in chipDefs
        count = 0
        if chipDef.key = "all"
            count = m.allResults.count()
        else if counts <> invalid and counts[chipDef.key] <> invalid
            count = counts[chipDef.key]
        end if

        if count > 0 or chipDef.key = "all"
            text = chipDef.label + " (" + str(count).trim() + ")"

            ' Measure text width — base width on label length + padding
            chipWidth = len(text) * 13 + 30
            if chipWidth < 100 then chipWidth = 100

            ' Create chip group: background rect + label
            chipGroup = createObject("roSGNode", "Group")
            chipGroup.translation = [xPos, 0]

            chipBg = createObject("roSGNode", "Rectangle")
            chipBg.width = chipWidth
            chipBg.height = 40
            chipBg.color = "#2a2a4a"
            chipGroup.appendChild(chipBg)

            chipLabel = createObject("roSGNode", "Label")
            chipLabel.translation = [10, 8]
            chipLabel.text = text
            chipLabel.font = "font:SmallSystemFont"
            chipLabel.color = "#aaaaaa"
            chipLabel.width = chipWidth - 20
            chipGroup.appendChild(chipLabel)

            m.chipRow.appendChild(chipGroup)
            m.chipLabels.push({ group: chipGroup, bg: chipBg, label: chipLabel })
            m.chipCategories.push(chipDef.key)

            xPos = xPos + chipWidth + 10
        end if
    end for

    updateChipFocus()
end sub

sub updateChipFocus()
    for i = 0 to m.chipLabels.count() - 1
        chip = m.chipLabels[i]
        if i = m.chipIndex
            chip.bg.color = "#6366f1"
            chip.label.color = "#ffffff"
        else
            chip.bg.color = "#2a2a4a"
            chip.label.color = "#aaaaaa"
        end if
    end for
end sub

sub filterResults(category as string)
    if category = "all"
        m.filteredResults = m.allResults
    else
        filtered = []
        for each result in m.allResults
            if result.resultType = category
                filtered.push(result)
            end if
        end for
        m.filteredResults = filtered
    end if

    buildResultsGrid()
end sub

sub buildResultsGrid()
    ' Build a single-row RowList content
    rowContent = createObject("roSGNode", "ContentNode")
    rowNode = createObject("roSGNode", "ContentNode")
    rowNode.title = ""

    for each result in m.filteredResults
        itemNode = createObject("roSGNode", "ContentNode")

        ' Set poster/image based on result type
        if result.posterUrl <> invalid and result.posterUrl <> ""
            itemNode.HDPosterUrl = result.posterUrl
        else if result.headshotUrl <> invalid and result.headshotUrl <> ""
            itemNode.HDPosterUrl = result.headshotUrl
        else
            itemNode.HDPosterUrl = "pkg:/images/placeholder-poster.png"
        end if

        itemNode.title = result.name

        rowNode.appendChild(itemNode)
    end for

    if rowNode.getChildCount() > 0
        rowContent.appendChild(rowNode)
    end if

    m.resultsRowList.content = rowContent
    m.resultsRowList.numRows = 1

    if m.filteredResults.count() = 0
        m.noResultsLabel.text = "No results in this category"
        m.noResultsLabel.visible = true
    else
        m.noResultsLabel.visible = false
    end if
end sub

sub onResultSelected()
    selected = m.resultsRowList.rowItemSelected
    if selected = invalid then return

    itemIndex = selected[1]
    if itemIndex < 0 or itemIndex >= m.filteredResults.count() then return

    result = m.filteredResults[itemIndex]
    print "[MM] SearchResultsScreen: selected " ; result.resultType ; " — " ; result.name

    if result.resultType = "movie"
        m.top.playRequested = result
    else if result.resultType = "series"
        m.top.episodePickerRequested = result
    else if result.resultType = "collection"
        m.top.collectionSelected = result
    else if result.resultType = "tag"
        m.top.tagSelected = result
    else if result.resultType = "genre"
        m.top.genreSelected = result
    else if result.resultType = "actor"
        m.top.actorSelected = result
    end if
end sub

' ---- Focus Management ----

sub setFocusTarget(target as string)
    m.focusTarget = target
    if target = "chips"
        m.chipRow.setFocus(true)
    else if target = "grid"
        m.resultsRowList.setFocus(true)
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if m.focusTarget = "chips"
        if key = "down"
            if m.filteredResults.count() > 0
                setFocusTarget("grid")
                return true
            end if
        else if key = "left"
            if m.chipIndex > 0
                m.chipIndex = m.chipIndex - 1
                updateChipFocus()
                filterResults(m.chipCategories[m.chipIndex])
                return true
            end if
        else if key = "right"
            if m.chipIndex < m.chipCategories.count() - 1
                m.chipIndex = m.chipIndex + 1
                updateChipFocus()
                filterResults(m.chipCategories[m.chipIndex])
                return true
            end if
        else if key = "OK"
            ' Pressing OK on a chip moves focus to the grid
            if m.filteredResults.count() > 0
                setFocusTarget("grid")
            end if
            return true
        end if
    else if m.focusTarget = "grid"
        if key = "up"
            ' Always allow up from grid to return to chips
            ' RowList with 1 row — up from row 0 should go to chips
            if m.resultsRowList.rowItemFocused <> invalid
                rowIndex = m.resultsRowList.rowItemFocused[0]
                if rowIndex = 0
                    setFocusTarget("chips")
                    return true
                end if
            end if
        end if
    end if

    return false
end function
