sub init()
    m.ring = m.top.findNode("ring")
    m.name = m.top.findNode("name")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.name.text = c.title
    m.top.findNode("avatar").uri = c.HDPosterUrl
    m.top.findNode("plus").text = c.ShortDescriptionLine1
    m.top.findNode("lock").text = c.ShortDescriptionLine2
end sub

sub onFocusPercent()
    p = m.top.focusPercent
    if p > 0.5 then
        m.ring.color = "0xFFFFFFFF"
        m.name.color = "0xFFFFFFFF"
    else
        m.ring.color = "0xFFFFFF00"
        m.name.color = "0xBBBBBBFF"
    end if
end sub
