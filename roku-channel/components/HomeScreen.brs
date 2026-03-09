sub init()
    print "[MM] HomeScreen: init"
    m.rowList = m.top.findNode("rowList")
    m.loadingLabel = m.top.findNode("loadingLabel")
    m.emptyLabel = m.top.findNode("emptyLabel")

    m.rowList.observeField("rowItemSelected", "onRowItemSelected")
    m.top.observeField("focusedChild", "onFocusChanged")
end sub

sub onFocusChanged()
    if m.top.hasFocus()
        print "[MM] HomeScreen: group received focus, delegating to rowList"
        m.rowList.setFocus(true)
    end if
end sub

sub onContentSet()
    content = m.top.content
    m.loadingLabel.visible = false

    if content = invalid or content.getChildCount() = 0
        print "[MM] HomeScreen: onContentSet — no content, showing empty state"
        m.emptyLabel.visible = true
        m.rowList.visible = false
        return
    end if

    ' Check if there are any items across all rows
    totalItems = 0
    for i = 0 to content.getChildCount() - 1
        row = content.getChild(i)
        rowTitle = ""
        if row <> invalid then rowTitle = row.title
        rowCount = row.getChildCount()
        totalItems = totalItems + rowCount
        print "[MM] HomeScreen: row " ; str(i).trim() ; " '" ; rowTitle ; "' — " ; str(rowCount).trim() ; " items"
    end for

    if totalItems = 0
        print "[MM] HomeScreen: onContentSet — all rows empty, showing empty state"
        m.emptyLabel.visible = true
        m.rowList.visible = false
        return
    end if

    print "[MM] HomeScreen: onContentSet — " ; str(content.getChildCount()).trim() ; " rows, " ; str(totalItems).trim() ; " total items"
    m.emptyLabel.visible = false
    m.rowList.visible = true
    m.rowList.content = content
    m.rowList.setFocus(true)
end sub

sub onRowItemSelected()
    sel = m.rowList.rowItemSelected
    if sel = invalid then return

    rowIndex = sel[0]
    colIndex = sel[1]

    content = m.rowList.content
    if content = invalid then return

    row = content.getChild(rowIndex)
    if row = invalid then return

    item = row.getChild(colIndex)
    if item = invalid then return

    print "[MM] HomeScreen: onRowItemSelected — row=" ; str(rowIndex).trim() ; " col=" ; str(colIndex).trim() ; " title=" ; item.title
    m.top.selectedItem = item
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false

    if key = "OK"
        ' RowList handles this via rowItemSelected
        return false
    end if

    return false
end function
