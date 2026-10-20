sub init()
    m.tile = m.top.findNode("tile")
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
    if not m.top.gridHasFocus then p = 0
    m.ring.opacity = p * 0.5 ' blanco translúcido: se ve el resaltado sin color sólido
    if p > 0.5 then m.name.color = "0xFFFFFFFF" else m.name.color = "0xBBBBBBFF"
end sub
