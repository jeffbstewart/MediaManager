sub init()
end sub

sub onItemContentChanged()
    itemData = m.top.itemContent
    if itemData = invalid then return

    m.top.findNode("nameLabel").text = itemData.name
    m.top.findNode("snapshotPoster").uri = itemData.snapshotUrl
end sub
