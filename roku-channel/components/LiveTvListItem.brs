sub init()
    m.guideNumberLabel = m.top.findNode("guideNumberLabel")
    m.nameLabel = m.top.findNode("nameLabel")
    m.networkLabel = m.top.findNode("networkLabel")
    m.qualityLabel = m.top.findNode("qualityLabel")
end sub

sub onItemContentChanged()
    content = m.top.itemContent
    if content = invalid then return

    guideNumber = ""
    if content.guideNumber <> invalid then guideNumber = content.guideNumber
    m.guideNumberLabel.text = guideNumber

    guideName = ""
    if content.guideName <> invalid then guideName = content.guideName
    m.nameLabel.text = guideName

    network = ""
    if content.networkAffiliation <> invalid then network = content.networkAffiliation
    m.networkLabel.text = network

    ' Build star string from reception quality
    quality = 0
    if content.receptionQuality <> invalid then quality = content.receptionQuality
    stars = ""
    for i = 1 to 5
        if i <= quality
            stars = stars + chr(9733) ' filled star
        else
            stars = stars + chr(9734) ' empty star
        end if
    end for
    m.qualityLabel.text = stars
end sub
