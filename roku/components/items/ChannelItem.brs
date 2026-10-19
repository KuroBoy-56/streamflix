sub init()
    m.bg = m.top.findNode("bg")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.top.findNode("name").text = c.title
    m.top.findNode("logo").uri = c.HDPosterUrl
    m.top.findNode("number").text = (m.top.index + 1).ToStr()
end sub

sub onFocusPercent()
    if m.top.focusPercent > 0.5 then m.bg.color = "0xE50914FF" else m.bg.color = "0x1A1D2900"
end sub
