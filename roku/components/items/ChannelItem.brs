sub init()
    m.bg = m.top.findNode("bg")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.top.findNode("name").text = c.title
    m.top.findNode("logo").uri = c.HDPosterUrl
end sub

sub onFocusPercent()
    m.bg.opacity = m.top.focusPercent
end sub
